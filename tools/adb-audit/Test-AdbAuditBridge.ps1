[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'Test-AdbAuditPolicy.ps1')

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

if (-not $IsLinux) {
    Write-Output 'ADB audit bridge integration fixture is Linux CI-only.'
    exit 0
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("cmfa-adb-audit-test-" + [guid]::NewGuid())
$outputDirectory = Join-Path $testRoot 'output'
$expandedDirectory = Join-Path $testRoot 'expanded'
$fakeAdb = Join-Path $testRoot 'fake-adb'

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    @'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == "devices -l" ]]; then
  if [[ "${CMFA_AUDIT_MULTIPLE_DEVICES:-}" == "1" ]]; then
    printf 'List of devices attached\nfake-serial device product:fixture model:CI_Device transport_id:1\nsecond-serial device product:fixture model:CI_Device_2 transport_id:2\n'
    exit 0
  fi
  printf 'List of devices attached\nfake-serial device product:fixture model:CI_Device transport_id:1\n'
  exit 0
fi
if [[ "$*" == "-s fake-serial shell pm path com.example.target" ]]; then
  printf 'package:/data/app/com.example.target/base.apk\n'
  exit 0
fi
if [[ "$*" == "-s fake-serial shell getprop ro.product.model" ]]; then printf 'CI Device\n'; exit 0; fi
if [[ "$*" == "-s fake-serial shell getprop ro.build.version.release" ]]; then printf '16\n'; exit 0; fi
if [[ "$*" == "-s fake-serial shell getprop ro.build.version.sdk" ]]; then printf '36\n'; exit 0; fi
if [[ "$*" == "-s fake-serial shell id" ]]; then printf 'uid=2000(shell) gid=2000(shell)\n'; exit 0; fi
if [[ "$*" == "-s fake-serial shell which tcpdump" ]]; then printf 'tcpdump unavailable\n' >&2; exit 1; fi
printf 'Authorization: Bearer ci-secret https://example.test/?token=query-secret latitude=31.2304\n'
'@ | Set-Content -LiteralPath $fakeAdb -Encoding utf8NoBOM
    & chmod +x $fakeAdb

    $bridge = Join-Path $PSScriptRoot 'Invoke-AdbAudit.ps1'

    $unredactedError = $null
    try {
        $null = & $bridge `
            -PackageName com.example.target `
            -AdbPath $fakeAdb `
            -Serial fake-serial `
            -OutputDirectory (Join-Path $testRoot 'unredacted-output') `
            -ConfirmAuthorizedUse `
            -RedactionMode None
    } catch {
        $unredactedError = $_
    }
    Assert-True ($null -ne $unredactedError) 'Unredacted export did not require a second confirmation.'
    Assert-True `
        ($unredactedError.Exception.Message -like '*ConfirmUnredactedExport*') `
        "Unexpected unredacted export error: $($unredactedError.Exception.Message)"

    $multipleDevicesBlocked = $false
    $multiDeviceOutput = Join-Path $testRoot 'multi-device-output'
    $env:CMFA_AUDIT_MULTIPLE_DEVICES = '1'
    try {
        & $bridge `
            -PackageName com.example.target `
            -AdbPath $fakeAdb `
            -OutputDirectory $multiDeviceOutput `
            -ConfirmAuthorizedUse | Out-Null
    } catch {
        $multipleDevicesBlocked = $_.Exception.Message -like '*Multiple authorized ADB devices*'
    } finally {
        Remove-Item Env:CMFA_AUDIT_MULTIPLE_DEVICES -ErrorAction SilentlyContinue
    }
    Assert-True $multipleDevicesBlocked 'Ambiguous multi-device selection was not rejected.'
    Assert-True ((Get-ChildItem -LiteralPath $multiDeviceOutput -Directory).Count -eq 0) 'An ambiguous device selection left an evidence directory behind.'

    $failedOutput = Join-Path $testRoot 'failed-output'
    $missingPackageBlocked = $false
    try {
        & $bridge `
            -PackageName com.example.missing `
            -AdbPath $fakeAdb `
            -Serial fake-serial `
            -OutputDirectory $failedOutput `
            -ConfirmAuthorizedUse | Out-Null
    } catch {
        $missingPackageBlocked = $_.Exception.Message -like '*not installed*'
    }
    Assert-True $missingPackageBlocked 'A missing target package was not rejected.'
    Assert-True ((Get-ChildItem -LiteralPath $failedOutput -Directory).Count -eq 0) 'A failed session left a generated evidence directory behind.'

    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
    $sentinel = Join-Path $outputDirectory 'keep-me.txt'
    Set-Content -LiteralPath $sentinel -Value 'preserve user output' -Encoding UTF8

    $zip = & $bridge `
        -PackageName com.example.target `
        -AdbPath $fakeAdb `
        -Serial fake-serial `
        -OutputDirectory $outputDirectory `
        -ConfirmAuthorizedUse `
        -AuthorizationReference 'CI-FIXTURE'

    Assert-True (Test-Path -LiteralPath $zip -PathType Leaf) 'The bridge did not produce a ZIP archive.'
    Assert-True ((Get-ChildItem -LiteralPath $outputDirectory -Directory).Count -eq 0) 'The generated session directory was not cleaned up.'
    Assert-True (Test-Path -LiteralPath $sentinel -PathType Leaf) 'The bridge removed an existing file from the selected output directory.'

    Expand-Archive -LiteralPath $zip -DestinationPath $expandedDirectory
    Assert-True ((Get-ChildItem -LiteralPath $expandedDirectory -File -Recurse).Count -eq 3) 'The ZIP contains files outside the strict report contract.'
    foreach ($requiredFile in @('manifest.json', 'records.jsonl', 'report.jsonl')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $expandedDirectory $requiredFile) -PathType Leaf) "Required ZIP file is missing: $requiredFile"
    }
    $manifestText = Get-Content -LiteralPath (Join-Path $expandedDirectory 'manifest.json') -Raw -Encoding UTF8
    $manifest = $manifestText | ConvertFrom-Json
    Assert-True ($manifest.protocol -eq 'cmfa-adb-audit') 'Unexpected manifest protocol.'
    Assert-True ($manifest.authorization.confirmed -eq $true) 'Authorization confirmation was not recorded.'
    Assert-True ($manifest.redaction.applied -eq $true) 'Default redaction was not recorded.'
    Assert-True ($manifest.deviceInfo.model -eq 'CI Device') 'Device metadata was not recorded.'
    Assert-True ($manifest.deviceInfo.serial -match '^sha256:[0-9a-f]{16}$') 'The default device serial was not pseudonymized.'
    Assert-True (-not $manifestText.Contains('fake-serial')) 'The default manifest exposed the raw ADB serial.'
    Assert-True ($manifest.capabilities.tcpdump -eq $false) 'Missing tcpdump was not recorded as a capability gap.'
    Assert-True ($manifest.limitations.Count -ge 5) 'Expected capability gaps were not recorded.'

    $records = Get-Content -LiteralPath (Join-Path $expandedDirectory 'records.jsonl') -Raw -Encoding UTF8
    foreach ($secret in @('ci-secret', 'query-secret', '31.2304', 'fake-serial')) {
        Assert-True (-not $records.Contains($secret)) "Generated records leaked a sensitive fixture value: $secret"
    }
    Assert-True ($records.Contains('session-started')) 'Session start record is missing.'
    Assert-True ($records.Contains('session-finished')) 'Session finish record is missing.'

    $reportLines = @(Get-Content -LiteralPath (Join-Path $expandedDirectory 'report.jsonl') -Encoding UTF8)
    Assert-True ($reportLines.Count -gt 1) 'Standalone report JSONL does not contain records.'
    $reportManifest = $reportLines[0] | ConvertFrom-Json
    Assert-True ($reportManifest.sessionId -eq $manifest.sessionId) 'Standalone JSONL manifest does not match the ZIP manifest.'

    Write-Output 'ADB audit bridge integration tests passed.'
} finally {
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
