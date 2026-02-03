# Start both API Server and Web UI
# Usage: .\start-all.ps1 [--port 8080] [--web-port 3000] [--index path/to/index] [--collocations path/to/collocations.bin]

param(
    [int]$Port = 8080,
    [int]$WebPort = 3000,
    [string]$Index = "d:\corpus_74m\index-hybrid"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir

# Check dependencies
$jarFile = Join-Path $projectRoot "target\word-sketch-lucene-1.0.0.jar"
if (-not (Test-Path $jarFile)) {
    Write-Error "JAR file not found: $jarFile"
    Write-Host "Please build with: mvn clean package"
    exit 1
}

if (-not (Test-Path $Index)) {
    Write-Error "Index directory not found: $Index"
    exit 1
}

# Auto-detect collocations file
$Collocations = ""
$collocationsSrc = Join-Path $Index "collocations_v2.bin"
if (Test-Path $collocationsSrc) {
    $Collocations = $collocationsSrc
}

Write-Host ""
Write-Host "================================" -ForegroundColor Cyan
Write-Host "Word Sketch Lucene - Full Stack" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Configuration:" -ForegroundColor Green
Write-Host "  API Port:  $Port"
Write-Host "  Web Port:  $WebPort"
if ($Collocations) {
    Write-Host "  Algorithm: PRECOMPUTED (O(1) instant lookup)" -ForegroundColor Cyan
} else {
    Write-Host "  Algorithm: SPAN_COUNT (on-the-fly queries)" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "Starting API Server (port $Port)..." -ForegroundColor Green
Write-Host "Starting Web Server (port $WebPort)..." -ForegroundColor Green
Write-Host ""

# Start API server in background
if ($Collocations) {
    $apiJob = Start-Job -ScriptBlock {
        param($jar, $idx, $p, $coll)
        & java -jar $jar server --index $idx --port $p --collocations $coll
    } -ArgumentList $jarFile, $Index, $Port, $Collocations
} else {
    $apiJob = Start-Job -ScriptBlock {
        param($jar, $idx, $p)
        & java -jar $jar server --index $idx --port $p
    } -ArgumentList $jarFile, $Index, $Port
}

# Give server time to start
Start-Sleep -Seconds 3

# Start web server in background
$webJob = Start-Job -ScriptBlock {
    param($path, $p)
    Push-Location $path
    python -m http.server $p
} -ArgumentList (Join-Path $projectRoot "webapp"), $WebPort

Write-Host ""
Write-Host "✓ Services started successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "API Server:" -ForegroundColor Cyan
Write-Host "  http://localhost:$Port/health"
Write-Host ""
Write-Host "Web Interface:" -ForegroundColor Cyan
Write-Host "  http://localhost:$WebPort" -ForegroundColor Yellow
Write-Host ""
Write-Host "Press Ctrl+C to stop all services..."
Write-Host ""

# Wait for jobs
Wait-Job $apiJob, $webJob
