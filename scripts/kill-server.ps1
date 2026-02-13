<#
.SYNOPSIS
Stops Word Sketch server processes and frees a TCP port (default 8080).

.DESCRIPTION
Finds processes listening on the specified TCP port and attempts to stop the ones
that look like `word-sketch-lucene` servers (inspects process command line).
Supports `-Force` to override command-line matching and `-WhatIf`/`-Confirm`.

.PARAMETER Port
TCP port to inspect (default: 8080).

.PARAMETER Force
When present will kill any process listening on the port (do not rely on cmdline match).

.EXAMPLE
.\.\scripts\kill-server.ps1

.EXAMPLE
.\.\scripts\kill-server.ps1 -Port 8080 -Force
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [int]
    $Port = 8080,

    [switch]
    $Force
)

function Get-ProcessCommandLine($procId) {
    try {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction Stop
        return $p.CommandLine
    } catch {
        return $null
    }
}

Write-Host "Checking listeners on port $Port..."

$listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue

if (-not $listeners) {
    Write-Host "No process is listening on port $Port." -ForegroundColor Yellow
} else {
    foreach ($l in $listeners) {
        $listenerPid = $l.OwningProcess
        $cmd = Get-ProcessCommandLine -procId $listenerPid
        if (-not $cmd) {
            try { $proc = Get-Process -Id $listenerPid -ErrorAction Stop; $cmd = "$($proc.Path) (Id=$listenerPid)" } catch { $cmd = "(pid $listenerPid)" }
        }

        Write-Host "Found listener PID ${listenerPid}`n  $cmd" -ForegroundColor Cyan

        $looksLikeWordSketch = $false
        if ($cmd -match 'word[-_ ]?sketch|word-sketch-lucene|word_sketch_lucene|word-sketch-lucene-') { $looksLikeWordSketch = $true }
        if ($Force) { $looksLikeWordSketch = $true }

        if ($looksLikeWordSketch) {
            $action = "Stop process PID $listenerPid"
            if ($PSCmdlet.ShouldProcess($action)) {
                try {
                    Stop-Process -Id $listenerPid -Force -ErrorAction Stop
                    Write-Host "Killed PID $listenerPid" -ForegroundColor Green
                } catch {
                    Write-Warning "Failed to kill PID ${listenerPid} - $($_.Exception.Message)"
                }
            } else {
                Write-Host "Skipped PID $listenerPid (ShouldProcess suppressed)" -ForegroundColor Yellow
            }
        } else {
            Write-Host "Skipping PID $listenerPid (command-line did not match). Use -Force to override." -ForegroundColor Yellow
        }
    }
}

Start-Sleep -Milliseconds 600

$remaining = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $remaining) {
    Write-Host "Port $Port is free now." -ForegroundColor Green
    exit 0
} else {
    Write-Warning "Port $Port is still listening by PID(s): $($remaining.OwningProcess -join ', ')"
    exit 2
}