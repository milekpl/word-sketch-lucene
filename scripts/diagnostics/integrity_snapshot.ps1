param(
    [string]$ApiBase = "http://localhost:8080",
    [int]$Top = 50,
    [string]$OutputDir = "diagnostics",
    [double]$SystemicRatioThreshold = 0.95,
    [double]$SystemicHeadwordFractionThreshold = 0.5,
    [int]$SystemicMinimumHeadwords = 5
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$rawPath = Join-Path $OutputDir "integrity_report.raw.json"
$summaryPath = Join-Path $OutputDir "integrity_summary.tsv"
$flagPath = Join-Path $OutputDir "integrity_systemic_flag.json"

$uri = "$ApiBase/api/diagnostics/collocation-integrity?top=$Top"
Write-Host "Fetching: $uri"

$raw = Invoke-WebRequest -Uri $uri -UseBasicParsing
[System.IO.File]::WriteAllText($rawPath, $raw.Content, [System.Text.Encoding]::UTF8)

$obj = $raw.Content | ConvertFrom-Json
$rows = @()

if ($obj.report) {
    foreach ($item in $obj.report) {
        $collocateCount = [int]$item.collocate_count
        $mismatchCount = [int]$item.mismatch_count
        $ratio = 0.0
        if ($collocateCount -gt 0) {
            $ratio = $mismatchCount / $collocateCount
        }
        $rows += [PSCustomObject]@{
            headword = [string]$item.headword
            collocate_count = $collocateCount
            mismatch_count = $mismatchCount
            mismatch_ratio = [Math]::Round($ratio, 4)
        }
    }
}

$lines = @("headword`tcollocate_count`tmismatch_count`tmismatch_ratio")
foreach ($r in $rows) {
    $lines += "$($r.headword)`t$($r.collocate_count)`t$($r.mismatch_count)`t$($r.mismatch_ratio)"
}
[System.IO.File]::WriteAllLines($summaryPath, $lines, [System.Text.Encoding]::UTF8)

$totalHeads = $rows.Count
$highMismatch = @($rows | Where-Object { $_.mismatch_ratio -ge $SystemicRatioThreshold }).Count
$requiredByFraction = [Math]::Ceiling($totalHeads * $SystemicHeadwordFractionThreshold)
$requiredHighCount = [Math]::Max($SystemicMinimumHeadwords, $requiredByFraction)
$systemic = ($totalHeads -gt 0 -and $highMismatch -ge $requiredHighCount)

$flag = [ordered]@{
    timestamp_utc = [DateTime]::UtcNow.ToString("o")
    api_base = $ApiBase
    top = $Top
    total_headwords = $totalHeads
    high_mismatch_headwords = $highMismatch
    systemic_ratio_threshold = $SystemicRatioThreshold
    systemic_headword_fraction_threshold = $SystemicHeadwordFractionThreshold
    systemic_minimum_headwords = $SystemicMinimumHeadwords
    systemic_threshold_count = $requiredHighCount
    systemic = $systemic
}

$flag | ConvertTo-Json -Depth 10 | Set-Content -Path $flagPath -Encoding UTF8

Write-Host "Wrote: $rawPath"
Write-Host "Wrote: $summaryPath"
Write-Host "Wrote: $flagPath"
if ($systemic) {
    Write-Warning "Systemic mismatch pattern detected."
} else {
    Write-Host "No systemic mismatch flag triggered."
}
