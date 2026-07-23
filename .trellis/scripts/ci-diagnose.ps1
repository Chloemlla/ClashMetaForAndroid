#requires -Version 5.1
<#
.SYNOPSIS
Diagnose a failed GitHub Actions run via gh CLI.

.DESCRIPTION
If -RunId is omitted, picks the latest failed run.
Fetches failed job logs with `gh run view --log-failed` and writes a markdown
summary to .trellis/workspace/ci-last-failure.md.
Does not run Gradle or Flutter.
#>
param(
    [Parameter(Mandatory = $false)]
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

$workspace = Join-Path $Root '.trellis\workspace'
New-Item -ItemType Directory -Force -Path $workspace | Out-Null
$summaryPath = Join-Path $workspace 'ci-last-failure.md'
$logPath = Join-Path $workspace 'ci-last-failure.log'

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $fields = 'databaseId,status,conclusion,displayTitle,headBranch,event,workflowName,url,createdAt'
    $listJson = gh run list --status failure --limit 1 --json $fields 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "gh run list --status failure failed: $listJson"
    }
    $failed = @($listJson | ConvertFrom-Json)
    if ($failed.Count -eq 0) {
        # Fallback: scan recent completed runs for conclusion=failure
        $recentJson = gh run list --limit 20 --json $fields 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "gh run list failed: $recentJson"
        }
        $failed = @($recentJson | ConvertFrom-Json | Where-Object { $_.conclusion -eq 'failure' } | Select-Object -First 1)
    }
    if ($failed.Count -eq 0) {
        throw 'No failed workflow runs found. Pass -RunId explicitly.'
    }
    $RunId = [string]$failed[0].databaseId
    Write-Host "Selected latest failure run id: $RunId"
}

$metaFields = 'databaseId,status,conclusion,displayTitle,headBranch,event,workflowName,url,createdAt,updatedAt,headSha'
$viewJson = gh run view $RunId --json $metaFields 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "gh run view $RunId failed: $viewJson"
}
$meta = $viewJson | ConvertFrom-Json

Write-Host "Fetching failed logs for run $RunId ..."
$logText = ''
try {
    $logOut = gh run view $RunId --log-failed 2>&1
    if ($LASTEXITCODE -ne 0) {
        $logText = "WARNING: gh run view --log-failed exited $LASTEXITCODE`n$logOut"
    } else {
        $logText = if ($logOut -is [array]) { $logOut -join "`n" } else { [string]$logOut }
    }
} catch {
    $logText = "ERROR capturing --log-failed: $_"
}

# Keep full log for agents; trim extremely large blobs in the md summary body.
Set-Content -Encoding UTF8 -Path $logPath -Value $logText

$maxSummaryChars = 120000
$logForMd = $logText
$truncated = $false
if ($logForMd.Length -gt $maxSummaryChars) {
    $logForMd = $logForMd.Substring($logForMd.Length - $maxSummaryChars)
    $truncated = $true
}

# Extract a short tail of ERROR/FAILED lines for the executive summary
$errorHints = @()
foreach ($line in ($logText -split "`r?`n")) {
    if ($line -match '(?i)(error|failed|failure|exception|##\[error\])') {
        $errorHints += $line.Trim()
        if ($errorHints.Count -ge 40) { break }
    }
}

$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$title = if ($meta.displayTitle) { $meta.displayTitle } else { $meta.workflowName }
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('# CI last failure')
[void]$sb.AppendLine()
[void]$sb.AppendLine("Generated: $now")
[void]$sb.AppendLine()
[void]$sb.AppendLine('## Run metadata')
[void]$sb.AppendLine()
[void]$sb.AppendLine("| Field | Value |")
[void]$sb.AppendLine("| --- | --- |")
[void]$sb.AppendLine("| RunId | $($meta.databaseId) |")
[void]$sb.AppendLine("| Workflow | $($meta.workflowName) |")
[void]$sb.AppendLine("| Title | $title |")
[void]$sb.AppendLine("| Status | $($meta.status) |")
[void]$sb.AppendLine("| Conclusion | $($meta.conclusion) |")
[void]$sb.AppendLine("| Branch | $($meta.headBranch) |")
[void]$sb.AppendLine("| Event | $($meta.event) |")
[void]$sb.AppendLine("| HeadSha | $($meta.headSha) |")
[void]$sb.AppendLine("| URL | $($meta.url) |")
[void]$sb.AppendLine("| CreatedAt | $($meta.createdAt) |")
[void]$sb.AppendLine("| UpdatedAt | $($meta.updatedAt) |")
[void]$sb.AppendLine()
[void]$sb.AppendLine('## Error hints (first matches)')
[void]$sb.AppendLine()
if ($errorHints.Count -eq 0) {
    [void]$sb.AppendLine('_No ERROR/FAILED lines matched; see full log below._')
} else {
    [void]$sb.AppendLine('```')
    foreach ($h in $errorHints) {
        [void]$sb.AppendLine($h)
    }
    [void]$sb.AppendLine('```')
}
[void]$sb.AppendLine()
[void]$sb.AppendLine('## Failed job logs')
[void]$sb.AppendLine()
[void]$sb.AppendLine("Full log path: ``$logPath``")
if ($truncated) {
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("> Log truncated to last $maxSummaryChars characters in this markdown; full text is in the .log file.")
}
[void]$sb.AppendLine()
[void]$sb.AppendLine('```')
[void]$sb.AppendLine($logForMd)
[void]$sb.AppendLine('```')
[void]$sb.AppendLine()
[void]$sb.AppendLine('## Ops next steps')
[void]$sb.AppendLine()
[void]$sb.AppendLine('1. Forensics: map failing job/step to source path.')
[void]$sb.AppendLine('2. Workflow audit: confirm declarative JDK/Go match `.github/workflows/*` only.')
[void]$sb.AppendLine('3. Code fix on a branch; push; no local Gradle/Flutter.')
[void]$sb.AppendLine('4. Verify with `gh run watch <id>` or poll via `.trellis/scripts/ci-status.ps1`.')
[void]$sb.AppendLine(('5. Optional rerun failed jobs: `.trellis/scripts/ci-rerun-failed.ps1 -RunId {0}' -f $RunId))

Set-Content -Encoding UTF8 -Path $summaryPath -Value $sb.ToString()

Write-Host "Summary written: $summaryPath"
Write-Host "Full log written: $logPath"
Write-Host "Run URL: $($meta.url)"
exit 0
