#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Build precomputed collocations index from hybrid Lucene index.

.DESCRIPTION
    Generates collocations.bin file containing precomputed top-K collocates
    for each lemma in the corpus. Enables O(1) query-time lookup.

.PARAMETER IndexPath
    Path to the hybrid Lucene index directory (required)

.PARAMETER StatsPath
    Path to stats.bin or stats.tsv (default: <IndexPath>/stats.bin)

.PARAMETER OutputPath
    Output path for collocations.bin (default: <IndexPath>/collocations.bin)

.PARAMETER WindowSize
    Context window size for cooccurrence (default: 5)

.PARAMETER TopK
    Maximum collocates per headword (default: 100)

.PARAMETER MinFrequency
    Minimum corpus frequency for headwords (default: 10)

.PARAMETER MinCooccurrence
    Minimum cooccurrence count to include (default: 2)

.PARAMETER Threads
    Number of parallel threads (default: CPU cores)

.PARAMETER JarPath
    Path to word-sketch-lucene JAR (default: target/word-sketch-lucene-*.jar)

.EXAMPLE
    .\build_collocations.ps1 -IndexPath target/index-quarter

.EXAMPLE
    .\build_collocations.ps1 -IndexPath target/index-74m -TopK 150 -MinFrequency 50 -Threads 8
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$IndexPath,
    
    [string]$StatsPath = "",
    
    [string]$OutputPath = "",
    
    [int]$WindowSize = 5,
    
    [int]$TopK = 100,
    
    [int]$MinFrequency = 10,
    
    [int]$MinCooccurrence = 2,
    
    [int]$Threads = 0,
    
    [string]$JarPath = ""
)

# Color output helpers
function Write-Step { param($msg) Write-Host "→ $msg" -ForegroundColor Cyan }
function Write-Success { param($msg) Write-Host "✓ $msg" -ForegroundColor Green }
function Write-Error { param($msg) Write-Host "✗ $msg" -ForegroundColor Red }
function Write-Info { param($msg) Write-Host "  $msg" -ForegroundColor Gray }

# Validate index path
if (-not (Test-Path $IndexPath)) {
    Write-Error "Index path not found: $IndexPath"
    exit 1
}

# Resolve absolute paths
$IndexPath = (Resolve-Path $IndexPath).Path

# Default stats path
if ([string]::IsNullOrEmpty($StatsPath)) {
    $StatsPath = Join-Path $IndexPath "stats.bin"
    if (-not (Test-Path $StatsPath)) {
        $StatsPath = Join-Path $IndexPath "stats.tsv"
    }
}

if (-not (Test-Path $StatsPath)) {
    Write-Error "Stats file not found at: $StatsPath"
    exit 1
}

$StatsPath = (Resolve-Path $StatsPath).Path

# Default output path
if ([string]::IsNullOrEmpty($OutputPath)) {
    $OutputPath = Join-Path $IndexPath "collocations.bin"
}

# Find JAR file
if ([string]::IsNullOrEmpty($JarPath)) {
    $jars = Get-ChildItem "target/word-sketch-lucene-*.jar" -ErrorAction SilentlyContinue
    if ($jars.Count -eq 0) {
        Write-Error "JAR file not found. Run: mvn package -DskipTests"
        exit 1
    }
    $JarPath = $jars[0].FullName
}

if (-not (Test-Path $JarPath)) {
    Write-Error "JAR file not found: $JarPath"
    exit 1
}

# CPU cores default
if ($Threads -eq 0) {
    $Threads = (Get-CimInstance -ClassName Win32_ComputerSystem).NumberOfLogicalProcessors
}

# Display configuration
Write-Host ""
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Magenta
Write-Host "   PRECOMPUTED COLLOCATIONS BUILDER" -ForegroundColor Magenta
Write-Host "═══════════════════════════════════════════════════════" -ForegroundColor Magenta
Write-Host ""
Write-Info "Index:          $IndexPath"
Write-Info "Stats:          $StatsPath"
Write-Info "Output:         $OutputPath"
Write-Host ""
Write-Info "Window size:    $WindowSize"
Write-Info "Top-K:          $TopK"
Write-Info "Min frequency:  $MinFrequency"
Write-Info "Min cooccur:    $MinCooccurrence"
Write-Info "Threads:        $Threads"
Write-Host ""

# Confirm if output exists
if (Test-Path $OutputPath) {
    $size = (Get-Item $OutputPath).Length / 1MB
    Write-Host "⚠️  Output file exists: $OutputPath ($([math]::Round($size, 1)) MB)" -ForegroundColor Yellow
    $response = Read-Host "Overwrite? (y/N)"
    if ($response -ne 'y' -and $response -ne 'Y') {
        Write-Info "Cancelled."
        exit 0
    }
}

# Build command
$cmd = @(
    "java"
    "-Xmx8G"
    "-cp"
    "`"$JarPath`""
    "pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationsBuilder"
    "`"$IndexPath`""
    "`"$StatsPath`""
    "`"$OutputPath`""
    "--window"
    "$WindowSize"
    "--top-k"
    "$TopK"
    "--min-freq"
    "$MinFrequency"
    "--min-cooc"
    "$MinCooccurrence"
    "--threads"
    "$Threads"
)

# Execute
Write-Step "Building collocations index..."
Write-Host ""

$startTime = Get-Date
$process = Start-Process -FilePath $cmd[0] -ArgumentList $cmd[1..($cmd.Length-1)] -NoNewWindow -Wait -PassThru

$elapsed = (Get-Date) - $startTime
$minutes = [math]::Floor($elapsed.TotalMinutes)
$seconds = $elapsed.Seconds

Write-Host ""

if ($process.ExitCode -eq 0) {
    if (Test-Path $OutputPath) {
        $size = (Get-Item $OutputPath).Length / 1MB
        Write-Success "Build completed in ${minutes}m ${seconds}s"
        Write-Info "Output: $OutputPath ($([math]::Round($size, 1)) MB)"
        
        # Suggest next steps
        Write-Host ""
        Write-Host "Next steps:" -ForegroundColor Cyan
        Write-Info "1. Test queries with PRECOMPUTED algorithm:"
        Write-Info "   curl http://localhost:8080/api/algorithm -X POST -d PRECOMPUTED"
        Write-Info "2. Compare performance:"
        Write-Info "   mvn test -Dtest=PrecomputedAlgorithmTest"
        Write-Host ""
    } else {
        Write-Error "Build completed but output file not found"
        exit 1
    }
} else {
    Write-Error "Build failed with exit code: $($process.ExitCode)"
    exit $process.ExitCode
}
