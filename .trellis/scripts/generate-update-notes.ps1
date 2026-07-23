#requires -Version 5.1
<#
.SYNOPSIS
Regenerate app/src/main/design/src/main/res/raw/update_notes.json from git log + curated highlights.

.DESCRIPTION
Trellis android/update-notes content pipeline. Does not run Gradle.
#>
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [int]$CommitCount = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Set-Location -LiteralPath $Root

$commitHash = (git rev-parse --short=7 HEAD).Trim()
$buildTime = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')

# Parse version from build.gradle.kts
$bg = Get-Content -Encoding UTF8 -Raw 'build.gradle.kts'
$versionName = if ($bg -match 'versionName\s*=\s*"([^"]+)"') { $Matches[1] } else { '0.0.0' }
$versionCode = if ($bg -match 'versionCode\s*=\s*(\d+)') { [int]$Matches[1] } else { 0 }

$assetPath = Join-Path $Root 'design\src\main\res\raw\update_notes.json'
$highlights = @()
$modules = @()
if (Test-Path $assetPath) {
    try {
        $prev = Get-Content -Encoding UTF8 -Raw $assetPath | ConvertFrom-Json
        if ($prev.highlights) { $highlights = @($prev.highlights) }
        if ($prev.modules) { $modules = @($prev.modules) }
    } catch {}
}

if ($highlights.Count -eq 0) {
    $highlights = @(
        [pscustomobject]@{ id = 'build'; title = "Build $versionName"; body = "commit $commitHash" }
    )
}
if ($modules.Count -eq 0) {
    $modules = @(
        [pscustomobject]@{ module = ':app'; summary = 'Open-source + update gates' }
    )
}

$commits = @()
git log -$CommitCount --pretty=format:'%h%x09%s' | ForEach-Object {
    if ([string]::IsNullOrWhiteSpace($_)) { return }
    $parts = $_ -split "`t", 2
    if ($parts.Count -lt 2) { return }
    $h = $parts[0].Trim(); $s = $parts[1].Trim()
    $type = 'chore'
    if ($s -match '^(merge|Merge)') { $type = 'merge' }
    elseif ($s -match '^feat') { $type = 'feat' }
    elseif ($s -match '^fix') { $type = 'fix' }
    elseif ($s -match '^docs') { $type = 'docs' }
    elseif ($s -match '^ci') { $type = 'ci' }
    elseif ($s -match '^chore') { $type = 'chore' }
    $commits += [pscustomobject]@{ hash = $h; subject = $s; type = $type }
}

$doc = [ordered]@{
    schemaVersion = 1
    identity = [ordered]@{
        commitHash = $commitHash
        buildTime = $buildTime
        versionName = $versionName
        versionCode = $versionCode
    }
    highlights = $highlights
    commits = $commits
    modules = $modules
}

$dir = Split-Path $assetPath -Parent
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$json = $doc | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($assetPath, $json, [System.Text.UTF8Encoding]::new($false))
Write-Host "Wrote $assetPath ($($commits.Count) commits, hash=$commitHash)"
