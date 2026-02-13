param(
    [string]$IndexPath = "index",
    [string]$IntegrityReportPath = "diagnostics/integrity_report.raw.json",
    [string]$OutputPath = "",
    [string]$Headwords = "",
    [int]$Window = 5,
    [int]$PerHeadLimit = 100,
    [string]$JarPath = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $IntegrityReportPath)) {
    throw "Integrity report not found: $IntegrityReportPath. Run scripts/diagnostics/integrity_snapshot.ps1 first."
}

if (-not $OutputPath) {
    $ts = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
    $OutputPath = "diagnostics/witness_$ts.tsv"
}

$outputDir = Split-Path -Parent $OutputPath
if ($outputDir) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

function Resolve-JarPath {
    param([string]$ExplicitJar)

    if ($ExplicitJar -and (Test-Path $ExplicitJar)) {
        return (Resolve-Path $ExplicitJar).Path
    }

    $jar = Get-ChildItem -Path "target" -Filter "word-sketch-lucene-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*original*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($jar) {
        return $jar.FullName
    }

    Write-Host "No runnable jar found in target/. Building package..."
    mvn -q -DskipTests package

    $jar = Get-ChildItem -Path "target" -Filter "word-sketch-lucene-*.jar" -File |
        Where-Object { $_.Name -notlike "*original*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "Could not find packaged jar in target/."
    }

    return $jar.FullName
}

$resolvedJar = Resolve-JarPath -ExplicitJar $JarPath

$argsList = @(
    "-cp", $resolvedJar,
    "pl.marcinmilkowski.word_sketch.tools.CollocationWitnessTool",
    "--index", $IndexPath,
    "--report", $IntegrityReportPath,
    "--output", $OutputPath,
    "--window", "$Window",
    "--per-head-limit", "$PerHeadLimit"
)

if ($Headwords) {
    $argsList += @("--headwords", $Headwords)
}

Write-Host "Generating witness TSV from: $IntegrityReportPath"
& java @argsList

Write-Host "Done: $OutputPath"
