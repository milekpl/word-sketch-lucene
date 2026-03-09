# BlackLab Indexing Script for CoNLL-U Corpus
# PowerShell script to download BlackLab, configure CoNLL-U format, and index corpus
#
# Usage:
#   .\index-conllu-blacklab.ps1 -InputFile corpus.conllu -OutputDir output-index/
#   .\index-conllu-blacklab.ps1 -InputFile corpus.conllu -OutputDir output-index/ -ShowDebug
#   .\index-conllu-blacklab.ps1 -InputFile corpus.conllu -OutputDir output-index/ -SkipDownload

param(
    [Parameter(Mandatory=$true)]
    [string]$InputFile,
    
    [Parameter(Mandatory=$true)]
    [string]$OutputDir,
    
    [string]$BlackLabVersion = "4.0.0",
    
    [string]$DownloadDir = "$env:TEMP\blacklab-download",
    
    [switch]$SkipDownload,
    
    [switch]$ShowDebug
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$timestamp] [$Level] $Message" -ForegroundColor $(if($Level -eq "ERROR") {"Red"} elseif($Level -eq "WARN") {"Yellow"} else {"Green"})
}

function Test-Java {
    # Check if java.exe exists in PATH
    $javaExe = $null
    try {
        $javaExe = Get-Command java -ErrorAction Stop
    } catch {
        # Try to find java in common locations
        $possiblePaths = @(
            "$env:JAVA_HOME\bin\java.exe",
            "C:\Program Files\Java\*\bin\java.exe",
            "C:\Program Files (x86)\Java\*\bin\java.exe"
        )
        foreach ($path in $possiblePaths) {
            $found = Get-ChildItem -Path $path -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) {
                $javaExe = $found
                break
            }
        }
    }
    
    if ($javaExe) {
        Write-Log "Java found: $($javaExe.Source)"
        return $true
    } else {
        Write-Log "Java not found in PATH. Please install Java 17 or higher." "ERROR"
        Write-Log "Download from: https://adoptium.net/" "ERROR"
        return $false
    }
}

function Download-BlackLab {
    param(
        [string]$Version,
        [string]$DownloadDir
    )
    
    # Check if already downloaded
    $engineJar = Get-ChildItem -Path "$DownloadDir\lib" -Filter "blacklab-engine-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($engineJar) {
        Write-Log "BlackLab v$Version already downloaded: $($engineJar.FullName)"
        return $DownloadDir
    }
    
    Write-Log "Downloading BlackLab v$Version..."
    
    $url = "https://github.com/instituutnederlandsetaal/BlackLab/releases/download/v$Version/blacklab-$Version-jar.zip"
    $zipFile = Join-Path $DownloadDir "blacklab.zip"
    
    # Create download directory
    if (!(Test-Path $DownloadDir)) {
        New-Item -ItemType Directory -Force -Path $DownloadDir | Out-Null
    }
    
    # Download
    Write-Log "Downloading from: $url"
    Invoke-WebRequest -Uri $url -OutFile $zipFile -UseBasicParsing
    
    # Extract
    Write-Log "Extracting..."
    Expand-Archive -Path $zipFile -DestinationPath $DownloadDir -Force
    
    Write-Log "BlackLab downloaded to: $DownloadDir"
    return $DownloadDir
}

function Create-CoNLLU-Config {
    param([string]$ConfigDir)
    
    $configContent = @"
# CoNLL-U format configuration for BlackLab
# Save this file as: conllu.blf.yaml

name: conllu
displayName: CoNLL-U Dependency Format
description: CoNLL-U format with word, lemma, POS, and dependency relations
mimeType: text/plain
fileExtension: conllu
encoding: utf-8

# Use the built-in CoNLL-U indexer
inputFormatClass: nl.inl.blacklab.indexers.config.DocIndexerCoNLLU

# Field configuration
annotatedFields:
  - name: contents
    displayName: Content
    summary: Tokenized and annotated text
    mainAnnotation: word
    annotations:
      - name: word
        displayName: Word form
        sensitivity: case
        forwardIndex: true
        storeOffsets: true
      - name: lemma
        displayName: Lemma
        sensitivity: insensitive
        forwardIndex: true
      - name: pos
        displayName: Part of speech (UPOS)
        sensitivity: sensitive
        forwardIndex: true
      - name: xpos
        displayName: Part of speech (XPOS)
        sensitivity: sensitive
        forwardIndex: false
      - name: feats
        displayName: Morphological features
        sensitivity: sensitive
        forwardIndex: false
      - name: deprel
        displayName: Dependency relation
        sensitivity: sensitive
        forwardIndex: false
        storeInDocIndex: true

# Metadata fields
metadata:
  - name: pid
    displayName: Document ID
    type: string
  - name: filename
    displayName: Filename
    type: string
"@

    $configPath = Join-Path $ConfigDir "conllu.blf.yaml"
    Set-Content -Path $configPath -Value $configContent -Encoding UTF8
    Write-Log "CoNLL-U configuration created: $configPath"
    
    return $configPath
}

function Index-Corpus {
    param(
        [string]$InputFile,
        [string]$OutputDir,
        [string]$BlackLabDir,
        [string]$ConfigPath
    )
    
    Write-Log "Starting corpus indexing..."
    Write-Log "Input: $InputFile"
    Write-Log "Output: $OutputDir"
    
    # Verify input file exists
    if (!(Test-Path $InputFile)) {
        throw "Input file not found: $InputFile"
    }
    
    # Get JAR files - check multiple possible locations
    $engineJar = $null
    
    # Try root directory first
    $engineJar = Get-ChildItem -Path $BlackLabDir -Filter "blacklab-engine-*.jar" -File | Select-Object -First 1
    
    # Try lib subdirectory
    if (!$engineJar) {
        $libDir = Join-Path $BlackLabDir "lib"
        if (Test-Path $libDir) {
            $engineJar = Get-ChildItem -Path $libDir -Filter "blacklab-engine-*.jar" -File | Select-Object -First 1
        }
    }
    
    # Try blacklab-engine subdirectory (from zip extraction)
    if (!$engineJar) {
        $engineSubdir = Get-ChildItem -Path $BlackLabDir -Directory -Filter "blacklab-engine-*" | Select-Object -First 1
        if ($engineSubdir) {
            $engineJar = Get-ChildItem -Path $engineSubdir.FullName -Filter "*.jar" -File | Select-Object -First 1
        }
    }
    
    if (!$engineJar) {
        Write-Log "Looking in: $BlackLabDir" "DEBUG"
        Write-Log "Contents:" "DEBUG"
        Get-ChildItem -Path $BlackLabDir | ForEach-Object { Write-Log "  - $($_.Name)" "DEBUG" }
        throw "BlackLab engine JAR not found in $BlackLabDir"
    }
    
    Write-Log "Found BlackLab engine: $($engineJar.FullName)"
    
    # Build classpath
    $libDir = Join-Path $BlackLabDir "lib"
    if (Test-Path $libDir) {
        $classpath = "$($engineJar.FullName);$libDir\*"
    } else {
        $classpath = $engineJar.FullName
    }
    
    # Create output directory
    if (!(Test-Path $OutputDir)) {
        New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    }
    
    # Build IndexTool command
    # Syntax: IndexTool create <indexdir> <inputfile> <format>
    # Use --index-type external for standard Lucene codec compatibility
    $indexToolArgs = @(
        "-cp", $classpath,
        "nl.inl.blacklab.tools.IndexTool",
        "create",
        "--index-type", "external",
        $OutputDir,
        $InputFile,
        "conll-u"  # Use the built-in format name
    )
    
    Write-Log "Running BlackLab IndexTool..."
    if ($ShowDebug) {
        Write-Log "Command: java $indexToolArgs"
    }
    
    # Run indexing
    $startTime = Get-Date
    java $indexToolArgs
    $exitCode = $LASTEXITCODE
    $endTime = Get-Date
    
    if ($exitCode -ne 0) {
        throw "Indexing failed with exit code $exitCode"
    }
    
    $duration = $endTime - $startTime
    Write-Log "Indexing completed in $($duration.Minutes)m $($duration.Seconds)s"
    
    # Show index statistics
    if (Test-Path $OutputDir) {
        $indexSize = (Get-ChildItem -Path $OutputDir -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
        Write-Log "Index size: $([math]::Round($indexSize, 2)) MB"
    }
}

# Main script
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  BlackLab CoNLL-U Corpus Indexing Script  " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Check Java
if (!(Test-Java)) {
    exit 1
}

# Download BlackLab if needed
$blackLabDir = Download-BlackLab -Version $BlackLabVersion -DownloadDir $DownloadDir

# Index corpus
try {
    Index-Corpus -InputFile $InputFile -OutputDir $OutputDir -BlackLabDir $blackLabDir
    
    Write-Host ""
    Write-Host "============================================" -ForegroundColor Green
    Write-Host "  Indexing Complete!                       " -ForegroundColor Green
    Write-Host "============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Index location: $OutputDir"
    Write-Host ""
    Write-Host "To query the index, use:" -ForegroundColor Yellow
    Write-Host "  java -jar concept-sketch-1.0.1.jar blacklab-query \" -ForegroundColor Yellow
    Write-Host "    --index $OutputDir \" -ForegroundColor Yellow
    Write-Host "    --lemma theory --deprel amod --limit 20" -ForegroundColor Yellow
    Write-Host ""
    
} catch {
    Write-Log "Indexing failed: $($_.Exception.Message)" "ERROR"
    if ($ShowDebug) {
        Write-Log $_.ScriptStackTrace "ERROR"
    }
    exit 1
}
