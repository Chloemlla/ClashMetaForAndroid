#requires -Version 5.1
<#
.SYNOPSIS
Show latest GitHub Actions runs and gate on main push failures.

.DESCRIPTION
Uses gh CLI only. Does not run Gradle or Flutter.
Lists the latest 10 workflow runs (status/conclusion/branch/url).
Exits 1 if any recent main-branch push run concluded as failure (not cancelled).
#>
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [int]$Limit = 10,
    [string]$MainBranch = 'main'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Set-Location -LiteralPath $Root

function Assert-Gh {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'gh CLI not found on PATH. Install: https://cli.github.com/'
    }
}

Assert-Gh

# Latest N runs across workflows
$fields = 'databaseId,status,conclusion,displayTitle,headBranch,event,workflowName,url,createdAt,updatedAt'
$runsJson = gh run list --limit $Limit --json $fields 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "gh run list failed: $runsJson"
}

$runs = $runsJson | ConvertFrom-Json
if (-not $runs) {
    Write-Host 'No workflow runs found.'
    exit 0
}

Write-Host ("{0,-12} {1,-12} {2,-28} {3,-24} {4}" -f 'STATUS', 'CONCLUSION', 'WORKFLOW', 'BRANCH', 'URL')
Write-Host ('-' * 120)

foreach ($r in $runs) {
    $status = if ($r.status) { $r.status } else { '-' }
    $conclusion = if ($r.conclusion) { $r.conclusion } else { '-' }
    $wf = if ($r.workflowName) { $r.workflowName } else { '-' }
    $branch = if ($r.headBranch) { $r.headBranch } else { '-' }
    $url = if ($r.url) { $r.url } else { '-' }
    if ($wf.Length -gt 26) { $wf = $wf.Substring(0, 23) + '...' }
    if ($branch.Length -gt 22) { $branch = $branch.Substring(0, 19) + '...' }
    Write-Host ("{0,-12} {1,-12} {2,-28} {3,-24} {4}" -f $status, $conclusion, $wf, $branch, $url)
}

# Gate: any latest main push run that failed (exclude cancelled / skipped / success / in_progress)
$mainFields = 'databaseId,status,conclusion,displayTitle,headBranch,event,workflowName,url,createdAt'
$mainJson = gh run list --branch $MainBranch --event push --limit $Limit --json $mainFields 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "gh run list (main push) failed: $mainJson"
}

$mainRuns = @($mainJson | ConvertFrom-Json)
$failedMain = @($mainRuns | Where-Object {
    $_.status -eq 'completed' -and
    $_.conclusion -eq 'failure'
})

if ($failedMain.Count -gt 0) {
    Write-Host ''
    Write-Host "GATE FAIL: $($failedMain.Count) main push run(s) concluded failure (not cancelled):" -ForegroundColor Red
    foreach ($f in $failedMain) {
        $title = if ($f.displayTitle) { $f.displayTitle } else { $f.workflowName }
        Write-Host ("  - [{0}] {1}  {2}" -f $f.databaseId, $title, $f.url)
    }
    exit 1
}

Write-Host ''
Write-Host "GATE OK: no main push failures in latest $Limit main push run(s)." -ForegroundColor Green
exit 0
