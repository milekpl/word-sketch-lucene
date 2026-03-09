#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Corpus Indexing Script for ConceptSketch
.DESCRIPTION
    Indexes line-segmented text files (one sentence per line) or CoNLL-U files
.PARAMETER InputFile
    Input file (line-segmented text or CoNLL-U)
.PARAMETER OutputDir
    Output directory for Lucene index
.PARAMETER Tagger
    Tagger to use: simple or udpipe (default: udpipe)
.PARAMETER Language
    Language code for UDPipe (default: en)
.PARAMETER Format
    Input format: text (default) or conllu
.PARAMETER BatchSize
    Batch size for indexing (default: 1000)
.EXAMPLE
    .\index_corpus.ps1 sentences.txt data\index --tagger udpipe --language en
    Index line-segmented English text using UDPipe
.EXAMPLE
    .\index_corpus.ps1 corpus.conllu data\index --format conllu
    Index pre-tagged CoNLL-U file
#>

param(
    [string]$InputFile,
    [string]$OutputDir,
    [string]$Tagger = "udpipe",
    [string]$Language = "en",
    [string]$Format = "text",
    [int]$BatchSize = 1000,
    [switch]$Help
)

if ($Help -or (-not $InputFile) -or (-not $OutputDir)) {
    Write-Host @"
ConceptSketch - Corpus Indexing Script

Usage: .\index_corpus.ps1 <input_file> <output_dir> [OPTIONS]

Arguments:
  input_file     Input file (line-segmented text or CoNLL-U)
  output_dir     Output directory for Lucene index

Options:
  -Tagger T      Tagger to use: simple (default: udpipe)
                 'udpipe' - Best results, requires UDPipe model
                 'simple' - Fallback, limited coverage
  -Language L    Language code for UDPipe (default: en)
                 Options: en, pl, de, es, fr, it, pt, ru, cs, etc.
  -Format F      Input format: text (default), conllu
                 'text'   - One sentence per line
                 'conllu' - CoNLL-U format (pre-tagged)
  -BatchSize N   Batch size for indexing (default: 1000)
  -Help          Show this help message

Examples:
  .\index_corpus.ps1 sentences.txt data\index --tagger udpipe --language en
  .\index_corpus.ps1 corpus.conllu data\index --format conllu
  .\index_corpus.ps1 sentences.txt data\index --tagger simple

"@
    exit 0
}

# Colors
$Colors = @{
    Info = "Cyan"
    Success = "Green"
    Warn = "Yellow"
    Error = "Red"
}

function Write-Info { Write-Host "[INFO]   $($args[0])" -ForegroundColor $Colors.Info }
function Write-Success { Write-Host "[OK]     $($args[0])" -ForegroundColor $Colors.Success }
function Write-Warn { Write-Host "[WARN]   $($args[0])" -ForegroundColor $Colors.Warn }
function Write-Error { Write-Host "[ERROR]  $($args[0])" -ForegroundColor $Colors.Error }

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "ConceptSketch - Corpus Indexing" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# Check input file
if (-not (Test-Path $InputFile)) {
    Write-Error "Input file not found: $InputFile"
    exit 1
}

# Create output directory
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

# Get project root
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Get-Item $ScriptDir).FullName
$JarFile = Join-Path $ProjectRoot "target/concept-sketch-1.0.0.jar"

# Check for JAR file
if (-not (Test-Path $JarFile)) {
    Write-Info "JAR not found, building project..."
    Set-Location $ProjectRoot
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-22"
    $env:MAVEN_OPTS = "-Xmx512m"

    try {
        mvn clean package -DskipTests -q
        if (-not (Test-Path $JarFile)) {
            throw "Build failed"
        }
        Write-Success "Build complete"
    }
    catch {
        Write-Error "Build failed: $_"
        exit 1
    }
}

Write-Info "Input file:  $InputFile"
Write-Info "Output dir:  $OutputDir"
Write-Info "Format:      $Format"
Write-Info "Tagger:      $Tagger"
if ($Format -eq "text") {
    Write-Info "Language:    $Language"
}
Write-Info "Batch size:  $BatchSize"
Write-Host ""

# Run the appropriate indexing command
Set-Location $ProjectRoot

if ($Format -eq "conllu") {
    Write-Info "Indexing CoNLL-U file..."
    $cmdArgs = @(
        "-jar", $JarFile,
        "conllu",
        "--input", $InputFile,
        "--output", $OutputDir,
        "--commit", $BatchSize
    )
}
elseif ($Tagger -eq "udpipe") {
    Write-Info "Indexing text with UDPipe tagger..."
    $cmdArgs = @(
        "-jar", $JarFile,
        "index",
        "--corpus", $InputFile,
        "--output", $OutputDir,
        "--language", $Language,
        "--batch", $BatchSize
    )
}
else {
    Write-Info "Indexing text with simple tagger..."
    $cmdArgs = @(
        "-jar", $JarFile,
        "index",
        "--corpus", $InputFile,
        "--output", $OutputDir,
        "--language", "simple",
        "--batch", $BatchSize
    )
}

try {
    & java @cmdArgs
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Success "Indexing complete!"
        Write-Host ""
        Write-Info "Index location: $OutputDir"
        Write-Host ""
        Write-Info "Usage examples:"
        Write-Info "  java -jar $JarFile query --index $OutputDir --lemma house"
        Write-Info "  java -jar $JarFile server --index $OutputDir"
        Write-Host ""
    }
    else {
        Write-Error "Indexing failed with exit code: $LASTEXITCODE"
        exit $LASTEXITCODE
    }
}
catch {
    Write-Error "Indexing failed: $_"
    exit 1
}
