# Authorized ADB Audit Contract

## 1. Scope / Trigger

Use this contract whenever changing `tools/adb-audit/`, the Android audit importer/policy/UI, or the `cmfa-adb-audit` archive protocol.

The feature is limited to explicitly authorized test devices and packages. The Windows companion collects evidence through an existing ADB executable; Android validates, stores, and summarizes imported evidence. Neither side may silently install tools or certificates, change proxy/VPN/DNS settings, grant permissions, obtain root, bypass TLS pinning, inject hooks, or upload evidence.

Missing DNS, HTTPS plaintext, private-file, packet, or runtime-hook evidence is a capability gap, never proof that the target performed no such activity.

## 2. Signatures

Companion entry point:

```powershell
./tools/adb-audit/Invoke-AdbAudit.ps1 `
  -PackageName <android.package> `
  -OutputDirectory <parent-directory> `
  -ConfirmAuthorizedUse `
  [-AdbPath <file = C:\adb\adb.exe>] `
  [-Serial <authorized-device-serial>] `
  [-AuthorizationReference <text>] `
  [-RedactionMode Default|None = Default] `
  [-ConfirmUnredactedExport] `
  [-KeepDirectory] `
  [-CommandTimeoutSeconds <5..600 = 120>] `
  [-PcapPath <file>] [-PcapMetadataPath <file>] `
  [-MitmLogPath <file>] [-MitmJsonPath <file>] `
  [-FridaLogPath <file>] [-FridaJsonPath <file>] `
  [-ExternalArtifactDirectory <flat-directory>]
```

ADB is launched with `ProcessStartInfo.ArgumentList`; package names and serials are arguments, never interpolated shell command text.

Android boundary:

```kotlin
object AuditReportImporter {
    fun import(context: Context, input: InputStream): AuditReportSummary
}

data class AuditReportSummary(
    val sessionId: String,
    val packageName: String,
    val limitations: List<String>,
    val evidenceFiles: List<String>,
    val redactionApplied: Boolean,
    val authorizationReference: String?,
    val deviceLabel: String?,
)
```

The non-exported `AuditReportActivity` opens ZIP/JSONL through `ActivityResultContracts.OpenDocument`; parsing and storage run on `Dispatchers.IO`.

## 3. Contracts

### Companion and ADB

- Require PowerShell 7+, an existing leaf `AdbPath`, `ConfirmAuthorizedUse`, a package matching `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`, and a directory-valued output path.
- Select only `adb devices -l` rows in state `device`. Zero authorized devices fails; multiple authorized devices require `-Serial`; an explicit serial must match one authorized row.
- Confirm installation with `adb -s <serial> shell pm path <package>` before collection.
- Use these argument-array commands: `getprop` for model/version/SDK; `dumpsys package <package>`; `cmd package dump <package>`; `ps -A`; `ss -tunap`; `dumpsys netstats`; `dumpsys connectivity`; `dumpsys location`; `logcat -d -v threadtime -t 2000`; `find /sdcard/Android/data/<package> -maxdepth 3 -type f`; `id`; and `which tcpdump`.
- Every ADB process has the configured timeout. Timeout kills the process tree when possible and fails the session; a nonzero optional collection command instead becomes a sanitized limitation.
- The output directory is a parent. Create a unique `cmfa-audit-<UTC timestamp>-<session prefix>` child and sibling ZIP; never overwrite an existing ZIP or recursively delete the supplied parent.

### Redaction, authorization, and capabilities

- Default mode redacts known credentials/tokens, Android/device identifiers, emails, and precise coordinates from companion-generated text. The selected serial is stored as `sha256:<first 16 lowercase hex>`.
- `RedactionMode=None` requires `ConfirmUnredactedExport`; otherwise fail before collection. External artifacts are copied as reviewed raw inputs and remain explicitly marked unredacted.
- Manifest authorization metadata is required: `confirmed=true`, UTC `confirmedAt`, safe `scope`, and optional sanitized `reference`. Redaction metadata is required: consistent `applied`/`mode`, safe `scope`, and `externalArtifactsRedacted=false` for companion archives.
- `capabilities` has exactly these Boolean keys: `adb`, `root`, `tcpdump`, `pcapdroid`, `dns`, `mitmproxy`, `httpsParameters`, `frida`, `runtimeHooks`.
- Root/tcpdump failures and absent PCAPdroid, mitmproxy, or Frida inputs must be written to `limitations`. PCAP inputs control `pcapdroid`/`dns`; proxy inputs control `mitmproxy`/`httpsParameters`; Frida inputs control `frida`/`runtimeHooks`. The importer validates and displays gaps but does not infer missing evidence.

### Archive and JSONL

Allowed ZIP layout is strict:

```text
manifest.json
records.jsonl
report.jsonl
artifacts/              # optional directory
  <flat-safe-name>      # optional files; no nested paths
```

- No other root files or directories are allowed. Entry names are normalized to `/`, unique, relative, control/bidi-free, and contain no empty, `.`, or `..` segment.
- `manifest.json` uses protocol `cmfa-adb-audit`, version `1`, a UUID `sessionId`, validated package, ordered start/finish instants, `deviceInfo`, authorization/redaction metadata, exact capabilities, limitations, and `artifactHashes`.
- Each nonblank `records.jsonl` line is one JSON object with the same `sessionId` and `packageName`, an `Instant`-parseable timestamp, safe nonblank `source` and `kind`, Boolean `redacted` equal to the manifest, and string `data`.
- `report.jsonl` is the manifest first, followed by the same nonblank record lines as `records.jsonl`. ZIP import requires manifest and records to match; standalone JSONL is accepted only when `artifactHashes` is empty.
- Every `artifacts/<name>` file has exactly one manifest SHA-256 entry keyed by its flat name; every hash is 64 hex characters and must match the extracted bytes.

Limits enforced while producing/importing:

| Boundary | Limit |
|---|---:|
| Total uncompressed evidence | 64 MiB |
| One artifact/archive entry | 48 MiB |
| Manifest | 1 MiB |
| `records.jsonl`, `report.jsonl`, or standalone JSONL | 16 MiB each |
| ZIP entries / JSONL records | 512 / 10,000 |
| Companion external artifacts | 500 files |
| Entry name / safe metadata text | 512 / 4,096 characters |
| Capability-gap strings | 128 entries |

### Android storage and UI

- Detect ZIP by `PK` signature, not extension or MIME; otherwise parse as standalone JSONL.
- Extract only after policy and canonical-path containment checks into `filesDir/audit-reports/<random UUID>`.
- On any validation/import failure, delete only that newly created target. On success, retain validated files in app-private storage and return the summary rendered by the UI.
- The UI shows package, session, device label, redaction warning, optional authorization reference, evidence filenames, and all limitations.
- Android VPN authorization is an explicit user-consent/status flow only. This screen does not start PCAP capture, run Windows ADB, install a CA, modify a proxy, or inject Frida.

### Cleanup and non-destructive behavior

- Successful companion runs delete only the generated session directory unless `KeepDirectory`; the ZIP remains.
- Failed runs remove the generated session directory unless `KeepDirectory` and remove only a newly created partial ZIP. Existing files in `OutputDirectory`, including sentinels, remain untouched.
- PowerShell script-scope cleanup traps also observe errors raised before their textual declaration. Initialize cleanup paths as the first executable setup, before StrictMode/version/module validation can throw; null-guard them before path operations, and preserve the original validation error instead of masking it with cleanup failure.
- Missing optional artifact paths add limitations; unsafe names, limit violations, package/device ambiguity, missing authorization, timeouts, or protocol violations fail closed.

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Invalid package, missing ADB leaf, PowerShell < 7, or missing authorization switch | Reject before evidence collection |
| `RedactionMode=None` without second confirmation | Reject unredacted export |
| No authorized device | Reject |
| Multiple authorized devices without serial | Reject; never guess |
| Explicit serial is not an authorized `device` row | Reject |
| Target package is absent | Reject; clean the generated session per the `KeepDirectory` contract |
| ADB command exceeds 5–600 second configured bound | Kill it and fail the session |
| Optional snapshot command returns nonzero | Continue and append a sanitized capability limitation |
| Root/tcpdump/PCAP/proxy/hook unavailable | Set capability false and describe the evidence gap |
| Optional artifact path is missing | Continue with a limitation |
| Artifact name is unsafe or size/count budget is exceeded | Reject; clean generated output per the cleanup contract |
| ZIP has traversal, absolute/drive path, bidi/control text, duplicate, nested, or unexpected entry | Reject and delete only the new import directory |
| Required ZIP root file is absent | Reject as incomplete |
| Manifest protocol/version, UUID, package, timestamps, consent, redaction, capabilities, limitations, or device metadata is invalid | Reject |
| Finish precedes start | Reject |
| Record session/package/redaction differs from manifest, or required field/type is absent | Reject |
| `report.jsonl` manifest/records differ from ZIP counterparts | Reject |
| Artifact is unhashed, hash has no file, or SHA-256 mismatches | Reject |
| Any entry/archive/record/metadata limit is exceeded | Reject while streaming; do not retain partial evidence |
| Standalone JSONL declares artifacts | Reject and require ZIP import |

## 5. Good / Base / Bad Cases

- Good: One explicitly selected authorized device, valid package, default redaction, and reviewed PCAP/proxy/hook artifacts produce a validated ZIP whose raw artifacts are hashed and clearly marked unredacted.
- Base: ADB-only collection succeeds without root/tcpdump/external tools; the report still imports and prominently lists incomplete private-file, DNS, HTTPS, packet, and runtime-hook coverage.
- Bad: Treating absent PCAP or HTTPS plaintext as “no network behavior.”
- Bad: Guessing between devices, interpolating package input into a shell string, retaining a traversal entry, or importing an artifact-bearing standalone JSONL.
- Bad: Deleting/recreating the user-selected output directory or removing existing files after a failed session.

## 6. Tests Required

All actual tests, Gradle, lint, and build commands run only in GitHub Actions. The `ADB audit bridge tests` step in `build-debug.yaml`, `build-pre-release.yaml`, and `build-release.yaml` runs the Linux PowerShell fixture; JVM tests run through the workflow test runner.

- PowerShell policy assertions: valid/invalid package forms, default secret/identifier/email/coordinate redaction, `None` preservation, and metadata control-character normalization.
- Companion fixture assertions: second confirmation for unredacted output with its original diagnostic preserved; ambiguous serial and missing package rejection; failure cleanup; preservation of a sentinel in the output parent; strict three-root-file ZIP; pseudonymized serial; authorization/redaction metadata; capability gaps; no fixture-secret leakage; start/finish records; standalone-report parity.
- Android policy assertions: traversal/absolute/bidi/control rejection, flat artifact names, SHA-256 format, exact Boolean capability set, consent/redaction consistency, UTC/session ordering, safe metadata, and all size/count constants.
- Android importer assertions: valid ZIP and standalone JSONL round trips; duplicate/unexpected/nested entries; session/package/redaction mismatch; report mismatch; missing/extra/bad artifact hashes; every size/count boundary; artifact-bearing JSONL rejection; and deletion of only the failed import target.
- UI assertions/review: system picker is used; import runs off the main thread; redaction/auth/device/evidence/gaps are rendered; VPN consent never starts capture or changes device security settings.

## 7. Wrong vs Correct

### Wrong

```powershell
& $AdbPath "-s $Serial shell dumpsys package $PackageName"
```

```powershell
Set-StrictMode -Version Latest
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb executable not found: $AdbPath"
}
$root = $null # Too late for the validation error above.
$outputPrefix = $null
trap {
    if ($root.StartsWith($outputPrefix)) { Remove-Item $root -Recurse -Force }
    throw
}
```

```kotlin
File(target, zipEntry.name).outputStream()
```

These forms permit ambiguous shell parsing or archive path escape and omit the evidence contract.

### Correct

```powershell
Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'package', $PackageName)
```

```powershell
# First executable setup in the script body, before StrictMode or validation.
$root = $null
$outputPrefix = $null
$zip = $null
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
trap {
    if (-not $KeepDirectory -and $root -and $outputPrefix -and
        $root.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($zip -and $outputPrefix -and
        $zip.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    }
    throw
}
```

```kotlin
require(AuditReportPolicy.isAllowedArchiveFile(name))
val destination = File(target, name)
require(destination.canonicalPath.startsWith(target.canonicalPath + File.separator))
```

Pass each ADB argument separately, validate the complete archive contract before accepting it, and preserve every unavailable observation as an explicit limitation.
