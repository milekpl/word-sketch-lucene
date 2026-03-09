# Start both API Server and Web UI
# Usage: .\start-all.ps1 [--port 8080] [--web-port 3000] [--index path/to/index]
#
# NOTE: collocations support and related command-line options were deprecated
# along with the precomputed collocations file; the server now ignores
# `--collocations` entirely.

param(
    [int]$Port = 8080,
    [int]$WebPort = 3000,
    [string]$Index = 'D:\corpora_philsci\fpsyg_index'
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir

# Check dependencies
$jarFile = Join-Path $projectRoot "target\concept-sketch-1.5.0-shaded.jar"
if (-not (Test-Path $jarFile)) {
    Write-Error "JAR file not found: $jarFile"
    Write-Host "Please build with: mvn clean package"
    exit 1
}

if (-not (Test-Path $Index)) {
    Write-Error "Index directory not found: $Index"
    exit 1
}

# collocations_v2.bin and associated switching logic are no longer used

Write-Host ""
Write-Host "================================" -ForegroundColor Cyan
Write-Host 'ConceptSketch - Full Stack' -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""
Write-Host 'Configuration:' -ForegroundColor Green
Write-Host "  API Port:  $Port"
Write-Host "  Web Port:  $WebPort"
Write-Host "  Index path: $Index"
Write-Host ""
Write-Host "Starting API Server (port $Port)..." -ForegroundColor Green
Write-Host "Starting Web Server (port $WebPort)..." -ForegroundColor Green
Write-Host ""

# Start API server in background (collocations argument removed)
$apiJob = Start-Job -ScriptBlock {
    param($jar, $idx, $p)
    & java -jar $jar server --index $idx --port $p
} -ArgumentList $jarFile, $Index, $Port

# Give server time to start
Start-Sleep -Seconds 3

# Start web server in background
$webJob = Start-Job -ScriptBlock {
    param($path, $p)
    Push-Location $path
    python -m http.server $p
} -ArgumentList (Join-Path $projectRoot "webapp"), $WebPort

Write-Host ""
Write-Host 'Services started successfully!' -ForegroundColor Green
Write-Host ""
Write-Host 'API Server:' -ForegroundColor Cyan
Write-Host "  http://localhost:$Port/health"
Write-Host ""
Write-Host 'Web Interface:' -ForegroundColor Cyan
Write-Host "  http://localhost:$WebPort" -ForegroundColor Yellow
Write-Host ""
Write-Host 'Press Ctrl+C to stop all services...'
Write-Host ""

# Wait for jobs
Wait-Job $apiJob, $webJob
