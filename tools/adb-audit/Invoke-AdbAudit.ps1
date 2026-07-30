[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PackageName,
    [string]$AdbPath = 'C:\adb\adb.exe',
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
    [switch]$KeepDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PackageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') { throw 'PackageName is not a valid Android package name.' }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "adb executable not found: $AdbPath" }
if (-not $ConfirmAuthorizedUse) { throw 'ConfirmAuthorizedUse is required before collecting device evidence.' }

$root = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $root | Out-Null
$sessionId = [guid]::NewGuid().ToString()
$started = [DateTime]::UtcNow
$artifactDir = Join-Path $root 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
$records = New-Object System.Collections.Generic.List[object]
$limitations = New-Object System.Collections.Generic.List[string]

function Invoke-Adb([string[]]$Arguments) {
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $AdbPath
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($arg in $Arguments) { [void]$psi.ArgumentList.Add($arg) }
    $p = New-Object Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    [pscustomobject]@{ ExitCode = $p.ExitCode; Output = $out; Error = $err }
}

function Add-Record([string]$Source, [string]$Kind, [string]$Data) {
    $records.Add([ordered]@{ sessionId = $sessionId; timestamp = [DateTime]::UtcNow.ToString('o'); source = $Source; kind = $Kind; packageName = $PackageName; redacted = $false; data = $Data })
}

function Import-ExternalArtifact([string]$Path, [string]$Source, [string]$Kind) {
    if (-not $Path) { return $false }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $limitations.Add("$Kind artifact not found: $Path")
        return $false
    }
    $safeName = [IO.Path]::GetFileName($Path)
    $destination = Join-Path $artifactDir "$Source-$safeName"
    Copy-Item -LiteralPath $Path -Destination $destination -Force
    $size = (Get-Item -LiteralPath $destination).Length
    Add-Record $Source 'external-artifact' ([ordered]@{ file = "artifacts/$Source-$safeName"; kind = $Kind; bytes = $size } | ConvertTo-Json -Compress)
    return $true
}

$devices = Invoke-Adb @('devices','-l')
if ($devices.ExitCode -ne 0) { throw $devices.Error }
$device = ($devices.Output -split "`r?`n" | Where-Object { $_ -match '^\S+\s+device\s' } | Select-Object -First 1)
if (-not $device) { throw 'No authorized ADB device found.' }
$serial = ($device -split '\s+')[0]
Add-Record 'adb' 'device' $device

function Collect([string]$Kind, [string[]]$Command) {
    $result = Invoke-Adb (@('-s',$serial,'shell') + $Command)
    if ($result.ExitCode -ne 0) { $limitations.Add("$Kind unavailable: $($result.Error.Trim())"); return }
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
Collect 'files' @('find','/sdcard/Android/data',$PackageName,'-maxdepth','3','-type','f')

$rootCheck = Invoke-Adb @('-s',$serial,'shell','id')
if ($rootCheck.Output -notmatch 'uid=0') { $limitations.Add('Root/tcpdump access unavailable; private files and packet payloads are not complete.') }

$pcapAvailable = (Import-ExternalArtifact $PcapPath 'pcapdroid' 'pcap')
$pcapMetadataAvailable = (Import-ExternalArtifact $PcapMetadataPath 'pcapdroid' 'metadata')
$mitmAvailable = (Import-ExternalArtifact $MitmLogPath 'mitmproxy' 'log')
$mitmJsonAvailable = (Import-ExternalArtifact $MitmJsonPath 'mitmproxy' 'json')
$fridaAvailable = (Import-ExternalArtifact $FridaLogPath 'frida' 'log')
$fridaJsonAvailable = (Import-ExternalArtifact $FridaJsonPath 'frida' 'json')
if ($ExternalArtifactDirectory) {
    if (-not (Test-Path -LiteralPath $ExternalArtifactDirectory -PathType Container)) {
        $limitations.Add("external artifact directory not found: $ExternalArtifactDirectory")
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

$manifest = [ordered]@{
    protocol = 'cmfa-adb-audit'; version = 1; sessionId = $sessionId; packageName = $PackageName
    device = $device; startedAt = $started.ToString('o'); finishedAt = [DateTime]::UtcNow.ToString('o')
    redaction = [ordered]@{ applied = $false; note = 'Raw ADB output may contain sensitive values. Review before sharing.' }
    authorization = [ordered]@{
        confirmed = $true
        confirmedAt = $started.ToString('o')
        reference = $AuthorizationReference
        scope = 'ADB metadata plus explicitly supplied external artifacts'
    }
    capabilities = [ordered]@{
        adb = $true; root = ($rootCheck.Output -match 'uid=0')
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
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'manifest.json') -Encoding UTF8
$records | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 8 } | Set-Content -LiteralPath (Join-Path $root 'records.jsonl') -Encoding UTF8
$zip = Join-Path (Split-Path $root -Parent) ((Split-Path $root -Leaf) + '.zip')
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $root '*') -DestinationPath $zip -CompressionLevel Optimal
Write-Output $zip
if (-not $KeepDirectory) { Remove-Item -LiteralPath $root -Recurse -Force }
