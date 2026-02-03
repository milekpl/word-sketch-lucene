# Start Word Sketch Lucene API Server
# Usage: .\start-server.ps1 [--port 8080] [--index path/to/index] [--collocations path/to/collocations.bin]

param(
    [int]$Port = 8080,
    [string]$Index = "d:\corpus_74m\index-hybrid"
)

# Get script directory
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir

# Check if JAR exists
$jarFile = Join-Path $projectRoot "target\word-sketch-lucene-1.0.0.jar"
if (-not (Test-Path $jarFile)) {
    Write-Error "JAR file not found: $jarFile"
    Write-Host "Please build with: mvn clean package"
    exit 1
}

# Check if index exists
if (-not (Test-Path $Index)) {
    Write-Error "Index directory not found: $Index"
    exit 1
}

# Auto-detect collocations file (optional parameter)
$Collocations = ""
$collocationsSrc = Join-Path $Index "collocations_v2.bin"
if (Test-Path $collocationsSrc) {
    $Collocations = $collocationsSrc
}

Write-Host ""
Write-Host "================================" -ForegroundColor Cyan
Write-Host "Word Sketch Lucene API Server" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Configuration:" -ForegroundColor Green
Write-Host "  Port:  $Port"
Write-Host "  Index: $Index"
if ($Collocations) {
    Write-Host "  Algorithm: PRECOMPUTED (O(1) instant lookup)" -ForegroundColor Cyan
} else {
    Write-Host "  Algorithm: SPAN_COUNT (on-the-fly queries)" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "Starting server..." -ForegroundColor Green
Write-Host ""

# Start server
if ($Collocations) {
    & java -jar $jarFile server --index $Index --port $Port --collocations $Collocations
} else {
    & java -jar $jarFile server --index $Index --port $Port
}
