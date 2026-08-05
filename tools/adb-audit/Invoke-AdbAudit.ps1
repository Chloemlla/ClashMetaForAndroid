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
    [ValidateRange(5, 600)][int]$CommandTimeoutSeconds = 120,
    # Traffic capture options (non-root)
    [switch]$EnablePCAPdroid,
    [ValidateRange(10, 600)][int]$CaptureDurationSeconds = 60,
    [string]$PCAPdroidApkUrl = 'https://github.com/emanuele-f/PCAPdroid/releases/download/v1.9.1/PCAPdroid_v1.9.1.apk',
    [switch]$EnableMitmProxy,
    [string]$MitmProxyAddress = '192.168.10.1:8080',
    [switch]$ConfigureWifiProxy
)

# Script-scope traps handle errors raised anywhere in this file, including guards
# below. Make cleanup state the first executable setup so version/module failures
# cannot be masked after StrictMode is enabled.
$root = $null
$outputPrefix = $null
$zip = $null

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
trap {
    # A bare throw outside catch creates ScriptHalted instead of rethrowing the
    # trapped error. Snapshot the ErrorRecord before cleanup and rethrow it.
    $trappedError = $_
    if ($root -and
        $outputPrefix -and
        -not $KeepDirectory -and
        $root.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $root -PathType Container)) {
        Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($zip -and
        $outputPrefix -and
        $zip.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $zip -PathType Leaf)) {
        Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    }
    throw $trappedError
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

# \u2500\u2500 Traffic capture helpers (non-root, VPN-based) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

function Install-PCAPdroid {
    $pkg = 'com.emanuelef.remote_capture'
    $check = Invoke-Adb @('-s',$serial,'shell','pm','list','packages',$pkg)
    if ($check.ExitCode -eq 0 -and $check.Output -match [regex]::Escape($pkg)) {
        Write-Host "PCAPdroid is already installed."
        return $true
    }
    $apkDir = Join-Path $root '..' 'pcapdroid-apk'
    New-Item -ItemType Directory -Force -Path $apkDir | Out-Null
    $apkPath = Join-Path $apkDir 'PCAPdroid.apk'
    try {
        Write-Host "Downloading PCAPdroid from $PCAPdroidApkUrl ..."
        $wc = New-Object Net.WebClient
        $wc.DownloadFile($PCAPdroidApkUrl, $apkPath)
        if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw 'Download failed' }
        $size = (Get-Item -LiteralPath $apkPath).Length
        Write-Host "Downloaded PCAPdroid APK ($([math]::Round($size/1MB, 1)) MB)."

        $remotePath = "/data/local/tmp/PCAPdroid.apk"
        $push = Invoke-Adb @('-s',$serial,'push',$apkPath,$remotePath)
        if ($push.ExitCode -ne 0) { throw "Failed to push APK to device: $($push.Error)" }

        $install = Invoke-Adb @('-s',$serial,'shell','pm','install','-r','-t','-g','--originating-uri','null','--referrer','null',$remotePath)
        $null = Invoke-Adb @('-s',$serial,'shell','rm','-f',$remotePath)
        $installFailed = ($install.ExitCode -ne 0) -or ($install.Output -match 'Failure') -or ($install.Error -match 'Failure')
        if ($installFailed) {
            Write-Host "PCAPdroid install blocked by device (Vivo/OriginOS restriction)."
            Write-Host "Install PCAPdroid manually from Google Play / F-Droid, then re-run."
            $limitations.Add("PCAPdroid not installed (Vivo ADB install restriction). Manual install required.")
            return $false
        }
        Write-Host "PCAPdroid installed successfully."
        return $true
    } finally {
        if (Test-Path -LiteralPath $apkDir -PathType Container) {
            Remove-Item -LiteralPath $apkDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Start-PCAPdroidCapture {
    $pkg = 'com.emanuelef.remote_capture'
    $outDir = '/sdcard/PCAPdroid'
    $outFile = "cmfa-capture-$sessionId.pcap"

    # Grant notification permission (Android 13+) and VPN permission (Android 14+)
    $null = Invoke-Adb @('-s',$serial,'shell','appops','set',$pkg,'POST_NOTIFICATIONS','allow')
    $null = Invoke-Adb @('-s',$serial,'shell','cmd','vpn','grant',$pkg)

    # Launch PCAPdroid main activity once to initialize (required for VPN setup on first run)
    $null = Invoke-Adb @('-s',$serial,'shell','am','start','-n',"$pkg/.MainActivity")
    Start-Sleep -Seconds 2
    $null = Invoke-Adb @('-s',$serial,'shell','input','keyevent','KEYCODE_HOME')

    $intentArgs = @(
        '-s',$serial,'shell','am','start','-n',"$pkg/.CaptureCtrl",
        '-e','action','start',
        '-e','pcap_dump_mode','file',
        '-e','pcap_dump_dir',$outDir,
        '-e','pcap_dump_file_name',$outFile
    )
    if ($EnableMitmProxy -and $MitmProxyAddress) {
        $intentArgs += @('-e','http_server',$MitmProxyAddress)
        Add-Record 'pcapdroid' 'capture-config' @{
            mode = 'file+mitmproxy'
            mitmProxy = $MitmProxyAddress
        } | ConvertTo-Json -Compress
    } else {
        Add-Record 'pcapdroid' 'capture-config' @{
            mode = 'file'
        } | ConvertTo-Json -Compress
    }

    $result = Invoke-Adb $intentArgs
    if ($result.ExitCode -ne 0) {
        $limitations.Add("PCAPdroid capture failed to start: $($result.Error)")
        return $null
    }
    # Wait for capture to initialize
    Start-Sleep -Seconds 3
    $pcapPath = "$outDir/$outFile"
    $check = Invoke-Adb @('-s',$serial,'shell','ls',$pcapPath)
    if ($check.ExitCode -ne 0) {
        $limitations.Add("PCAPdroid did not create output file; capture may not have started.")
        return $null
    }
    return $pcapPath
}

function Pull-PCAPdroidArtifact([string]$RemotePath) {
    if (-not $RemotePath) { return $null }
    $safeName = "pcapdroid-capture-$(Split-Path -Leaf $RemotePath)"
    $destination = Join-Path $artifactDir $safeName

    $pull = Invoke-Adb @('-s',$serial,'pull',$RemotePath,$destination)
    if ($pull.ExitCode -ne 0) {
        $limitations.Add("Failed to pull PCAPdroid capture: $($pull.Error)")
        return $null
    }
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        $limitations.Add("PCAPdroid capture file not found after pull.")
        return $null
    }
    $size = (Get-Item -LiteralPath $destination).Length
    $script:artifactBytes += $size
    $script:artifactCount += 1
    Add-Record 'pcapdroid' 'capture-pcap' ([ordered]@{
        file = "artifacts/$safeName"
        bytes = $size
        durationSeconds = $CaptureDurationSeconds
        mitmProxy = if ($EnableMitmProxy) { $MitmProxyAddress } else { $null }
    } | ConvertTo-Json -Compress)
    $pcapAvailable = $true
    return "artifacts/$safeName"
}

function Set-WifiProxy {
    $current = Invoke-Adb @('-s',$serial,'shell','settings','get','global','http_proxy')
    if ($current.ExitCode -eq 0 -and $current.Output.Trim() -ne 'null' -and [string]::IsNullOrWhiteSpace($current.Output)) {
        Add-Record 'adb-shell' 'proxy-override' @{
            previous = $current.Output.Trim()
            new = $MitmProxyAddress
        } | ConvertTo-Json -Compress
    }
    $result = Invoke-Adb @('-s',$serial,'shell','settings','put','global','http_proxy',$MitmProxyAddress)
    if ($result.ExitCode -ne 0) {
        $limitations.Add("Failed to set WiFi proxy: $($result.Error)")
    }
}

function Clear-WifiProxy {
    $null = Invoke-Adb @('-s',$serial,'shell','settings','put','global','http_proxy',':0')
    $null = Invoke-Adb @('-s',$serial,'shell','settings','delete','global','http_proxy')
}

function Stop-PCAPdroid {
    $pkg = 'com.emanuelef.remote_capture'
    $null = Invoke-Adb @('-s',$serial,'shell','am','start','-n',"$pkg/.CaptureCtrl",'-e','action','stop')
    Start-Sleep -Seconds 2
}

# \u2500\u2500 Frida pre-check (non-root advisory) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

# ── Traffic capture phase ─────────────────────────────────────────────────
$pcapRemotePath = $null
$captureStarted = $false
$pulled = $null

if ($EnablePCAPdroid) {
    Add-Record 'pcapdroid' 'capture-begin' ([ordered]@{
        durationSeconds = $CaptureDurationSeconds
        mitmProxy = if ($EnableMitmProxy) { $MitmProxyAddress } else { $null }
    } | ConvertTo-Json -Compress)

    if ($EnableMitmProxy -and $ConfigureWifiProxy) {
        Set-WifiProxy
        Add-Record 'adb-shell' 'proxy-configured' $MitmProxyAddress
    }

    $installResult = Install-PCAPdroid
    if ($installResult) {
        $pcapRemotePath = Start-PCAPdroidCapture
        if ($pcapRemotePath) {
            $captureStarted = $true
            Write-Host "Capturing traffic for $CaptureDurationSeconds seconds..."
            Start-Sleep -Seconds $CaptureDurationSeconds
            Stop-PCAPdroid
            $pulled = Pull-PCAPdroidArtifact $pcapRemotePath
            if ($pulled) {
                $pcapAvailable = $true
                $pcapMetadataAvailable = $true
            }
        }
    }

    if ($ConfigureWifiProxy) {
        Clear-WifiProxy
        Add-Record 'adb-shell' 'proxy-cleared' 'WiFi proxy restored to default'
    }

    Add-Record 'pcapdroid' 'capture-end' ([ordered]@{
        started = $captureStarted
        pcapCollected = ($null -ne $pulled)
    } | ConvertTo-Json -Compress)
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
