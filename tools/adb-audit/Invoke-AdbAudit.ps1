[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PackageName,
    [string]$AdbPath = 'C:\adb\adb.exe',
    [string]$Serial,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$PcapPath,
    [string]$PcapMetadataPath,
    [string]$MitmLogPath,
    [string]$MitmJsonPath,
    [string]$FridaLogPath,
    [string]$FridaJsonPath,
    [string]$ExternalArtifactDirectory,
    [Parameter(Mandatory = $true)][switch]$ConfirmAuthorizedUse,
    [string]$AuthorizationReference,
    [ValidateSet('Default', 'None')][string]$RedactionMode = 'Default',
    [switch]$ConfirmUnredactedExport,
    [switch]$KeepDirectory,
    [ValidateRange(5, 600)][int]$CommandTimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required.' }
Import-Module (Join-Path $PSScriptRoot 'AuditPolicy.psm1') -Force

if (-not (Test-AuditPackageName $PackageName)) { throw 'PackageName is not a valid Android package name.' }
$AdbPath = [IO.Path]::GetFullPath($AdbPath)
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "adb executable not found: $AdbPath" }
if (-not $ConfirmAuthorizedUse) { throw 'ConfirmAuthorizedUse is required before collecting device evidence.' }
if ($RedactionMode -eq 'None' -and -not $ConfirmUnredactedExport) {
    throw 'ConfirmUnredactedExport is required when RedactionMode is None.'
}
$safeAuthorizationReference = ConvertTo-AuditMetadataText `
    -Value (Protect-AuditText -Value $AuthorizationReference -Mode $RedactionMode) `
    -Fallback $null

$sessionId = [guid]::NewGuid().ToString()
$started = [DateTime]::UtcNow
$outputBase = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputBase -PathType Leaf) { throw 'OutputDirectory must be a directory.' }
New-Item -ItemType Directory -Force -Path $outputBase | Out-Null
$sessionName = "cmfa-audit-$($started.ToString('yyyyMMdd-HHmmss'))-$($sessionId.Substring(0, 8))"
$root = [IO.Path]::GetFullPath((Join-Path $outputBase $sessionName))
$outputPrefix = $outputBase.TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
) + [IO.Path]::DirectorySeparatorChar
if (-not $root.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Generated audit session path escaped the selected output directory.'
}
New-Item -ItemType Directory -Path $root | Out-Null
$zip = $null
trap {
    if (-not $KeepDirectory -and
        $root.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $root -PathType Container)) {
        Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($zip -and
        $zip.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $zip -PathType Leaf)) {
        Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    }
    throw
}
$artifactDir = Join-Path $root 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
$records = New-Object System.Collections.Generic.List[object]
$limitations = New-Object System.Collections.Generic.List[string]
$script:artifactBytes = 0L
$script:artifactCount = 0
$maxArtifactBytes = 48L * 1024L * 1024L
$maxTotalArtifactBytes = 64L * 1024L * 1024L
$maxArtifactCount = 500
$maxTextEvidenceBytes = 16L * 1024L * 1024L

function Invoke-Adb([string[]]$Arguments) {
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $AdbPath
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($arg in $Arguments) { [void]$psi.ArgumentList.Add($arg) }
    $p = New-Object Diagnostics.Process
    $p.StartInfo = $psi
    try {
        [void]$p.Start()
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $errTask = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($CommandTimeoutSeconds * 1000)) {
            try { $p.Kill($true) } catch { $p.Kill() }
            $p.WaitForExit()
            throw "ADB command timed out after $CommandTimeoutSeconds seconds."
        }
        $out = $outTask.GetAwaiter().GetResult()
        $err = $errTask.GetAwaiter().GetResult()
        [pscustomobject]@{ ExitCode = $p.ExitCode; Output = $out; Error = $err }
    } finally {
        $p.Dispose()
    }
}

function Add-Record([string]$Source, [string]$Kind, [string]$Data) {
    $protectedData = Protect-AuditText -Value $Data -Mode $RedactionMode
    $records.Add([ordered]@{
        sessionId = $sessionId
        timestamp = [DateTime]::UtcNow.ToString('o')
        source = $Source
        kind = $Kind
        packageName = $PackageName
        redacted = ($RedactionMode -ne 'None')
        data = $protectedData
    })
}

function Import-ExternalArtifact([string]$Path, [string]$Source, [string]$Kind) {
    if (-not $Path) { return $false }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $limitations.Add("$Kind artifact was not found at the provided path.")
        return $false
    }
    $safeName = [IO.Path]::GetFileName($Path)
    if ([string]::IsNullOrWhiteSpace($safeName) -or
        $safeName.Length -gt 180 -or
        $safeName -match '[\x00-\x1F\x7F\u202A-\u202E\u2066-\u2069/\\]') {
        throw "$Kind artifact has an unsafe file name."
    }
    $size = (Get-Item -LiteralPath $Path).Length
    if ($size -gt $maxArtifactBytes -or ($script:artifactBytes + $size) -gt $maxTotalArtifactBytes) {
        throw "$Kind artifact exceeds the audit archive size limit."
    }
    if ($script:artifactCount -ge $maxArtifactCount) {
        throw 'Too many external artifacts were supplied for one audit archive.'
    }
    $destinationName = "$Source-$Kind-$safeName"
    $destination = Join-Path $artifactDir $destinationName
    Copy-Item -LiteralPath $Path -Destination $destination
    $script:artifactBytes += $size
    $script:artifactCount += 1
    Add-Record $Source 'external-artifact' ([ordered]@{
        file = "artifacts/$destinationName"
        kind = $Kind
        bytes = $size
        contentRedacted = $false
    } | ConvertTo-Json -Compress)
    return $true
}

$devices = Invoke-Adb @('devices','-l')
if ($devices.ExitCode -ne 0) { throw $devices.Error }
$authorizedDevices = @($devices.Output -split "`r?`n" | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' })
if ($authorizedDevices.Count -eq 0) { throw 'No authorized ADB device found.' }
if ($Serial) {
    $device = $authorizedDevices | Where-Object { ($_ -split '\s+')[0] -eq $Serial } | Select-Object -First 1
    if (-not $device) { throw "The requested ADB device is not authorized: $Serial" }
} elseif ($authorizedDevices.Count -gt 1) {
    $availableSerials = ($authorizedDevices | ForEach-Object { ($_ -split '\s+')[0] }) -join ', '
    throw "Multiple authorized ADB devices found. Pass -Serial with one of: $availableSerials"
} else {
    $device = $authorizedDevices[0]
}
$serial = ($device -split '\s+')[0]
function Protect-DeviceIdentifier([string]$Value) {
    if ($RedactionMode -eq 'None') {
        return ConvertTo-AuditMetadataText -Value $Value -Fallback 'unknown'
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        $hash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
        return "sha256:$($hash.Substring(0, 16))"
    } finally {
        $sha.Dispose()
    }
}
$reportedSerial = Protect-DeviceIdentifier $serial
$deviceRecord = if ($RedactionMode -eq 'None') {
    $device
} else {
    "$reportedSerial$($device.Substring($serial.Length))"
}
Add-Record 'adb' 'device' $deviceRecord
Add-Record 'companion' 'session-started' ([ordered]@{
    authorizedUse = $true
    authorizationReference = $safeAuthorizationReference
} | ConvertTo-Json -Compress)

$packagePath = Invoke-Adb @('-s',$serial,'shell','pm','path',$PackageName)
if ($packagePath.ExitCode -ne 0 -or $packagePath.Output -notmatch '(?m)^package:') {
    throw "Package is not installed on the selected device: $PackageName"
}

function Read-DeviceProperty([string]$Name) {
    $result = Invoke-Adb @('-s',$serial,'shell','getprop',$Name)
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.Output)) {
        $limitations.Add("Device property $Name is unavailable.")
        return 'unknown'
    }
    return ConvertTo-AuditMetadataText -Value $result.Output -Fallback 'unknown'
}

$deviceInfo = [ordered]@{
    serial = $reportedSerial
    model = Read-DeviceProperty 'ro.product.model'
    androidVersion = Read-DeviceProperty 'ro.build.version.release'
    sdk = Read-DeviceProperty 'ro.build.version.sdk'
}
Add-Record 'adb' 'device-info' ($deviceInfo | ConvertTo-Json -Compress)

function Collect([string]$Kind, [string[]]$Command) {
    $result = Invoke-Adb (@('-s',$serial,'shell') + $Command)
    if ($result.ExitCode -ne 0) {
        $reason = ConvertTo-AuditMetadataText `
            -Value (Protect-AuditText -Value $result.Error -Mode $RedactionMode) `
            -Fallback 'ADB command failed'
        $limitations.Add("$Kind unavailable: $reason")
        return
    }
    Add-Record 'adb-shell' $Kind $result.Output
}

Collect 'package' @('dumpsys','package',$PackageName)
Collect 'permissions' @('cmd','package','dump',$PackageName)
Collect 'processes' @('ps','-A')
Collect 'sockets' @('ss','-tunap')
Collect 'network-stats' @('dumpsys','netstats')
Collect 'connectivity' @('dumpsys','connectivity')
Collect 'location' @('dumpsys','location')
Collect 'logcat' @('logcat','-d','-v','threadtime','-t','2000')
Collect 'files' @('find',"/sdcard/Android/data/$PackageName",'-maxdepth','3','-type','f')

$rootCheck = Invoke-Adb @('-s',$serial,'shell','id')
$rootAvailable = $rootCheck.ExitCode -eq 0 -and $rootCheck.Output -match 'uid=0'
$tcpdumpCheck = Invoke-Adb @('-s',$serial,'shell','which','tcpdump')
$tcpdumpAvailable = $tcpdumpCheck.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($tcpdumpCheck.Output)
if (-not $rootAvailable) {
    $limitations.Add('Root access is unavailable; private app files and privileged process data are incomplete.')
}
if (-not $tcpdumpAvailable) {
    $limitations.Add('tcpdump is unavailable; ADB cannot collect packet payloads directly.')
}

$pcapAvailable = (Import-ExternalArtifact $PcapPath 'pcapdroid' 'pcap')
$pcapMetadataAvailable = (Import-ExternalArtifact $PcapMetadataPath 'pcapdroid' 'metadata')
$mitmAvailable = (Import-ExternalArtifact $MitmLogPath 'mitmproxy' 'log')
$mitmJsonAvailable = (Import-ExternalArtifact $MitmJsonPath 'mitmproxy' 'json')
$fridaAvailable = (Import-ExternalArtifact $FridaLogPath 'frida' 'log')
$fridaJsonAvailable = (Import-ExternalArtifact $FridaJsonPath 'frida' 'json')
if ($ExternalArtifactDirectory) {
    if (-not (Test-Path -LiteralPath $ExternalArtifactDirectory -PathType Container)) {
        $limitations.Add('The external artifact directory was not found at the provided path.')
    } else {
        Get-ChildItem -LiteralPath $ExternalArtifactDirectory -File | ForEach-Object {
            [void](Import-ExternalArtifact $_.FullName 'external' $_.Extension.TrimStart('.'))
        }
    }
}
if (-not ($pcapAvailable -or $pcapMetadataAvailable)) {
    $limitations.Add('DNS evidence requires an explicit PCAPdroid artifact; ADB snapshots do not expose complete DNS activity.')
}
if (-not ($mitmAvailable -or $mitmJsonAvailable)) {
    $limitations.Add('HTTPS parameters require an explicitly supplied mitmproxy artifact; ADB snapshots do not expose plaintext.')
}
if (-not ($fridaAvailable -or $fridaJsonAvailable)) {
    $limitations.Add('Runtime hook data requires an explicitly supplied Frida log; no injection is performed by this script.')
}

$finished = [DateTime]::UtcNow
Add-Record 'companion' 'session-finished' ([ordered]@{
    finishedAt = $finished.ToString('o')
    recordCount = $records.Count + 1
} | ConvertTo-Json -Compress)

$manifest = [ordered]@{
    protocol = 'cmfa-adb-audit'; version = 1; sessionId = $sessionId; packageName = $PackageName
    device = $deviceRecord; deviceInfo = $deviceInfo
    startedAt = $started.ToString('o'); finishedAt = $finished.ToString('o')
    redaction = [ordered]@{
        applied = ($RedactionMode -ne 'None')
        mode = $RedactionMode
        scope = 'Text records generated by this companion'
        externalArtifactsRedacted = $false
        note = if ($RedactionMode -eq 'None') {
            'Unredacted export was explicitly confirmed. Review all evidence before sharing.'
        } else {
            'Known credentials, identifiers, email addresses, and precise coordinates were redacted from text records. External artifacts remain user-reviewed raw inputs.'
        }
    }
    authorization = [ordered]@{
        confirmed = $true
        confirmedAt = $started.ToString('o')
        reference = $safeAuthorizationReference
        scope = 'ADB metadata plus explicitly supplied external artifacts'
    }
    capabilities = [ordered]@{
        adb = $true; root = $rootAvailable; tcpdump = $tcpdumpAvailable
        pcapdroid = ($pcapAvailable -or $pcapMetadataAvailable)
        dns = ($pcapMetadataAvailable -or $pcapAvailable)
        mitmproxy = ($mitmAvailable -or $mitmJsonAvailable)
        httpsParameters = ($mitmAvailable -or $mitmJsonAvailable)
        frida = ($fridaAvailable -or $fridaJsonAvailable)
        runtimeHooks = ($fridaAvailable -or $fridaJsonAvailable)
    }
    limitations = @($limitations)
    artifactHashes = [ordered]@{}
}
Get-ChildItem -LiteralPath $artifactDir -File | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest.artifactHashes[$_.Name] = $hash
}
$manifestPath = Join-Path $root 'manifest.json'
$recordsPath = Join-Path $root 'records.jsonl'
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$records | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 8 } | Set-Content -LiteralPath $recordsPath -Encoding UTF8
$reportJsonl = Join-Path $root 'report.jsonl'
$manifest | ConvertTo-Json -Compress -Depth 8 | Set-Content -LiteralPath $reportJsonl -Encoding UTF8
$records | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 8 } | Add-Content -LiteralPath $reportJsonl -Encoding UTF8
if ((Get-Item -LiteralPath $manifestPath).Length -gt 1L * 1024L * 1024L) {
    throw 'Generated audit manifest exceeds the 1 MiB Android import limit.'
}
foreach ($textEvidence in @($recordsPath, $reportJsonl)) {
    if ((Get-Item -LiteralPath $textEvidence).Length -gt $maxTextEvidenceBytes) {
        throw 'Generated audit text evidence exceeds the 16 MiB Android import limit.'
    }
}
$uncompressedBytes = (Get-ChildItem -LiteralPath $root -File -Recurse | Measure-Object -Property Length -Sum).Sum
if ($uncompressedBytes -gt 64L * 1024L * 1024L) {
    throw 'Generated audit evidence exceeds the 64 MiB Android import limit.'
}
$zipCandidate = [IO.Path]::GetFullPath((Join-Path $outputBase "$sessionName.zip"))
if (-not $zipCandidate.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Generated audit ZIP path escaped the selected output directory.'
}
if (Test-Path -LiteralPath $zipCandidate) { throw 'Generated audit ZIP path already exists.' }
$zip = $zipCandidate
Compress-Archive -Path (Join-Path $root '*') -DestinationPath $zip -CompressionLevel Optimal
if (-not $KeepDirectory) { Remove-Item -LiteralPath $root -Recurse -Force }
Write-Output $zip
