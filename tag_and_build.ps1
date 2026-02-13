#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Complete pipeline for tagging and indexing a large corpus.

.DESCRIPTION
    Processes a large corpus through the complete pipeline:
    1. UDPipe tags text to CoNLL-U format (using native file I/O to avoid Windows UTF-8 encoding issues)
    2. Single-pass processor builds Lucene index AND collocations simultaneously

    This script is designed for processing large corpora (1.5 GB+) efficiently.

.PARAMETER CorpusFile
    Input corpus file (UTF-8 encoded, one sentence per line).

.PARAMETER OutputDir
    Output directory for index and collocations.

.PARAMETER Model
    UDPipe model file path. Default: english-ewt.udpipe

.PARAMETER CommitInterval
    Lucene commit interval (number of sentences). Default: 50000

.PARAMETER MemoryLimit
    Maximum in-memory collocation entries before spilling to disk. Default: 10000000

.PARAMETER TopK
    Top-K collocates per headword to retain. Default: 100

.PARAMETER KeepConllu
    Keep the intermediate CoNLL-U file after processing.

.EXAMPLE
    .\tag_and_build.ps1 -CorpusFile corpus.txt -OutputDir index\
    Tag and index corpus.txt using default settings.

.EXAMPLE
    .\tag_and_build.ps1 -CorpusFile corpus.txt -OutputDir index\ -Model english-ewt.udpipe -TopK 200
    Use a specific UDPipe model and keep top 200 collocates per headword.

.EXAMPLE
    .\tag_and_build.ps1 -CorpusFile corpus.txt -OutputDir index\ -KeepConllu
    Keep the intermediate CoNLL-U file for debugging or reuse.

.NOTES
    Requirements:
    - UDPipe must be installed and available in PATH
    - Java 11+ must be available
    - The project must be built (mvn package) or will be built automatically
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$CorpusFile,
    
    [Parameter(Mandatory=$true)]
    [string]$OutputDir,
    
    [string]$Model = "english-ewt.udpipe",
    
    [int]$CommitInterval = 50000,
    
    [int]$MemoryLimit = 10000000,
    
    [int]$TopK = 100,
    
    [switch]$KeepConllu
)

# ============================================================================
# Helper Functions
# ============================================================================

function Write-Step {
    param([string]$Message)
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] [STEP]  $Message" -ForegroundColor Cyan
}

function Write-Info {
    param([string]$Message)
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] [INFO]  $Message" -ForegroundColor Gray
}

function Write-Success {
    param([string]$Message)
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] [DONE]  $Message" -ForegroundColor Green
}

function Write-ErrorMessage {
    param([string]$Message)
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] [ERROR] $Message" -ForegroundColor Red
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] [WARN]  $Message" -ForegroundColor Yellow
}

function Test-CommandExists {
    param([string]$Command)
    $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
}

function Get-FilesizeMB {
    param([string]$Path)
    if (Test-Path $Path) {
        return [math]::Round((Get-Item $Path).Length / 1MB, 1)
    }
    return 0
}

function Get-FilesizeGB {
    param([string]$Path)
    if (Test-Path $Path) {
        return [math]::Round((Get-Item $Path).Length / 1GB, 2)
    }
    return 0
}

function Test-DirectoryWritable {
    param([string]$Path)
    try {
        $testFile = Join-Path $Path ".writetest_$(Get-Random)"
        New-Item -ItemType File -Path $testFile -Force | Out-Null
        Remove-Item $testFile -Force
        return $true
    }
    catch {
        return $false
    }
}

# ============================================================================
# Script Setup
# ============================================================================

$ErrorActionPreference = "Stop"
$StartTime = Get-Date

# Track if we need to clean up
$Script:CleanupConllu = $true
$Script:ConlluFile = $null

# Ctrl+C handler
[Console]::TreatControlCAsInput = $false
try {
    [Console]::CancelKeyPress.Add_Invoked({
        param($sender, $e)
        Write-Warning "Interrupted by user!"
        if (-not $KeepConllu -and $Script:ConlluFile -and (Test-Path $Script:ConlluFile)) {
            Write-Info "Cleaning up intermediate file: $Script:ConlluFile"
            Remove-Item $Script:ConlluFile -Force -ErrorAction SilentlyContinue
        }
        exit 130
    })
}
catch {
    # CancelKeyPress may not be available in all hosts
}

# ============================================================================
# Header
# ============================================================================

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Single-Pass Pipeline: Tag + Index + Collocations" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ============================================================================
# Input Validation
# ============================================================================

Write-Step "Validating inputs..."

# Check corpus file exists
if (-not (Test-Path $CorpusFile)) {
    Write-ErrorMessage "Corpus file not found: $CorpusFile"
    exit 1
}
$CorpusFile = Resolve-Path $CorpusFile
$CorpusSizeGB = Get-FilesizeGB $CorpusFile
Write-Info "Corpus file: $CorpusFile ($CorpusSizeGB GB)"

# Check UDPipe model exists
if (-not (Test-Path $Model)) {
    Write-ErrorMessage "UDPipe model not found: $Model"
    Write-Info "Download models from: https://ufal.mff.cuni.cz/udpipe/2#download_models"
    exit 1
}
$Model = Resolve-Path $Model
Write-Info "UDPipe model: $Model"

# Create and check output directory
$OutputDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDir)
if (-not (Test-Path $OutputDir)) {
    try {
        New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
        Write-Info "Created output directory: $OutputDir"
    }
    catch {
        Write-ErrorMessage "Cannot create output directory: $OutputDir"
        Write-ErrorMessage $_.Exception.Message
        exit 1
    }
}

if (-not (Test-DirectoryWritable $OutputDir)) {
    Write-ErrorMessage "Output directory is not writable: $OutputDir"
    exit 1
}
Write-Info "Output directory: $OutputDir"

# Check UDPipe is available
if (-not (Test-CommandExists "udpipe")) {
    Write-ErrorMessage "UDPipe not found in PATH"
    Write-Info "Install UDPipe from: https://ufal.mff.cuni.cz/udpipe/2"
    Write-Info "Or via: pip install ufal.udpipe"
    exit 1
}
Write-Info "UDPipe: Available"

# Check Java is available
if (-not (Test-CommandExists "java")) {
    Write-ErrorMessage "Java not found in PATH"
    Write-Info "Install Java 11+ from: https://adoptium.net/"
    exit 1
}
$JavaVersion = (java -version 2>&1 | Select-Object -First 1)
Write-Info "Java: $JavaVersion"

# Find project root and JAR
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ScriptDir) {
    $ScriptDir = "."
}
$ProjectRoot = (Get-Item $ScriptDir).FullName
$JarFile = Join-Path $ProjectRoot "target/word-sketch-lucene-1.0.0.jar"

# Check/build JAR file
if (-not (Test-Path $JarFile)) {
    Write-Info "JAR file not found, building project..."
    Push-Location $ProjectRoot
    try {
        $env:JAVA_HOME = "C:\Program Files\Java\jdk-22"
        $env:MAVEN_OPTS = "-Xmx512m"
        mvn clean package -DskipTests -q 2>&1 | Out-Null
        if (-not (Test-Path $JarFile)) {
            throw "Build completed but JAR not found"
        }
        Write-Success "Project built successfully"
    }
    catch {
        Write-ErrorMessage "Failed to build project: $_"
        Pop-Location
        exit 1
    }
    Pop-Location
}
Write-Info "JAR file: $JarFile"

Write-Host ""

# ============================================================================
# Display Configuration
# ============================================================================

Write-Host "Configuration:" -ForegroundColor White
Write-Host "  Corpus:           $CorpusFile ($CorpusSizeGB GB)" -ForegroundColor Gray
Write-Host "  Output:           $OutputDir" -ForegroundColor Gray
Write-Host "  UDPipe Model:     $Model" -ForegroundColor Gray
Write-Host "  Commit Interval:  $CommitInterval" -ForegroundColor Gray
Write-Host "  Memory Limit:     $MemoryLimit" -ForegroundColor Gray
Write-Host "  Top-K:            $TopK" -ForegroundColor Gray
Write-Host "  Keep CoNLL-U:     $KeepConllu" -ForegroundColor Gray
Write-Host ""

# ============================================================================
# Step 1: UDPipe Tagging
# ============================================================================

$Step1Start = Get-Date
$ConlluFile = Join-Path $OutputDir "corpus.conllu"
$Script:ConlluFile = $ConlluFile

Write-Step "Step 1: UDPipe Tagging"
Write-Info "Running UDPipe with native file I/O (avoids Windows UTF-8 pipe issues)"
Write-Info "Command: udpipe --tokenizer=none --tag --parser=none $Model $CorpusFile --outfile=$ConlluFile"

try {
    $udpipeProcess = Start-Process -FilePath "udpipe" `
        -ArgumentList @("--tokenizer=none", "--tag", "--parser=none", $Model, $CorpusFile, "--outfile=$ConlluFile") `
        -NoNewWindow `
        -Wait `
        -PassThru

    if ($udpipeProcess.ExitCode -ne 0) {
        throw "UDPipe exited with code: $($udpipeProcess.ExitCode)"
    }
}
catch {
    Write-ErrorMessage "UDPipe tagging failed: $_"
    exit 1
}

$Step1Time = ((Get-Date) - $Step1Start).TotalSeconds
$ConlluSizeMB = Get-FilesizeMB $ConlluFile

if (-not (Test-Path $ConlluFile)) {
    Write-ErrorMessage "CoNLL-U file was not created"
    exit 1
}

Write-Success "Tagging complete: $ConlluSizeMB MB CoNLL-U in $([math]::Round($Step1Time, 1))s"
Write-Host ""

# ============================================================================
# Step 2: Single-Pass Processing
# ============================================================================

$Step2Start = Get-Date
$CollocationsFile = Join-Path $OutputDir "collocations.bin"

Write-Step "Step 2: Single-Pass Index + Collocations"
Write-Info "Building Lucene index and collocations in a single pass"
Write-Info "Command: java -Xmx8g -jar word-sketch-lucene-1.0.0.jar single-pass ..."

$JavaArgs = @(
    "-Xmx8g",
    "-jar", $JarFile,
    "single-pass",
    "--input", $ConlluFile,
    "--output", $OutputDir,
    "--collocations", $CollocationsFile,
    "--commit", $CommitInterval,
    "--memory", $MemoryLimit,
    "--top-k", $TopK
)

Write-Info "Full command: java $($JavaArgs -join ' ')"

try {
    $javaProcess = Start-Process -FilePath "java" `
        -ArgumentList $JavaArgs `
        -NoNewWindow `
        -Wait `
        -PassThru

    if ($javaProcess.ExitCode -ne 0) {
        throw "Java processor exited with code: $($javaProcess.ExitCode)"
    }
}
catch {
    Write-ErrorMessage "Single-pass processing failed: $_"
    if (-not $KeepConllu -and (Test-Path $ConlluFile)) {
        Write-Info "Cleaning up intermediate file: $ConlluFile"
        Remove-Item $ConlluFile -Force -ErrorAction SilentlyContinue
    }
    exit 1
}

$Step2Time = ((Get-Date) - $Step2Start).TotalSeconds
Write-Success "Processing complete in $([math]::Round($Step2Time, 1))s"
Write-Host ""

# ============================================================================
# Cleanup
# ============================================================================

if (-not $KeepConllu) {
    Write-Step "Cleanup"
    if (Test-Path $ConlluFile) {
        Remove-Item $ConlluFile -Force
        Write-Info "Removed intermediate CoNLL-U file: $ConlluFile"
    }
    Write-Success "Cleanup complete"
    Write-Host ""
}

# ============================================================================
# Results Summary
# ============================================================================

$TotalTime = ((Get-Date) - $StartTime).TotalSeconds

Write-Host "==========================================" -ForegroundColor Green
Write-Host "Pipeline Complete!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""

Write-Host "Output Files:" -ForegroundColor White
Write-Host "  Index:         $OutputDir" -ForegroundColor Gray
if (Test-Path $CollocationsFile) {
    $CollocationsSizeMB = Get-FilesizeMB $CollocationsFile
    Write-Host "  Collocations:  $CollocationsFile ($CollocationsSizeMB MB)" -ForegroundColor Gray
}
$StatsFile = Join-Path $OutputDir "stats.bin"
if (Test-Path $StatsFile) {
    $StatsSizeMB = Get-FilesizeMB $StatsFile
    Write-Host "  Statistics:    $StatsFile ($StatsSizeMB MB)" -ForegroundColor Gray
}
$LexiconFile = Join-Path $OutputDir "lexicon.bin"
if (Test-Path $LexiconFile) {
    $LexiconSizeMB = Get-FilesizeMB $LexiconFile
    Write-Host "  Lexicon:       $LexiconFile ($LexiconSizeMB MB)" -ForegroundColor Gray
}
if ($KeepConllu -and (Test-Path $ConlluFile)) {
    Write-Host "  CoNLL-U:       $ConlluFile ($ConlluSizeMB MB)" -ForegroundColor Gray
}

Write-Host ""
Write-Host "Timing:" -ForegroundColor White
Write-Host "  UDPipe:        $([math]::Round($Step1Time, 1))s" -ForegroundColor Gray
Write-Host "  Processing:    $([math]::Round($Step2Time, 1))s" -ForegroundColor Gray
Write-Host "  Total:         $([math]::Round($TotalTime, 1))s" -ForegroundColor Gray
Write-Host ""

Write-Host "Usage:" -ForegroundColor White
Write-Host "  java -jar $JarFile server --index $OutputDir --collocations $CollocationsFile" -ForegroundColor Gray
Write-Host ""

# Exit successfully
exit 0
