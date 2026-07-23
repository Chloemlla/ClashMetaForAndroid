#requires -Version 5.1
<#
.SYNOPSIS
Rerun failed jobs for a GitHub Actions workflow run.

.DESCRIPTION
Wraps `gh run rerun --failed <RunId>`. Does not run Gradle or Flutter.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$RunId,

    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
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

if ([string]::IsNullOrWhiteSpace($RunId)) {
    throw 'RunId is required. Example: .\.trellis\scripts\ci-rerun-failed.ps1 -RunId 1234567890'
}

Write-Host "Rerunning failed jobs for run $RunId ..."
$out = gh run rerun --failed $RunId 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "gh run rerun --failed $RunId failed: $out"
}

if ($out) { Write-Host $out }
Write-Host "Queued rerun of failed jobs for run $RunId."
Write-Host "Watch: gh run watch $RunId"
Write-Host "Or poll: .\.trellis\scripts\ci-status.ps1"
exit 0
