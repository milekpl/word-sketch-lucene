param(
    [string]$IndexPath = "index",
    [string]$CollocationsPath = "",
    [string]$OutputPath = "diagnostics/fingerprint.json",
    [string]$JarPath = ""
)

$ErrorActionPreference = "Stop"

if (-not $CollocationsPath) {
    $CollocationsPath = Join-Path $IndexPath "collocations.bin"
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

Write-Host "Generating fingerprint from index: $IndexPath"
java -cp $resolvedJar pl.marcinmilkowski.word_sketch.tools.IndexFingerprintTool `
    --index $IndexPath `
    --collocations $CollocationsPath `
    --output $OutputPath

Write-Host "Done: $OutputPath"
