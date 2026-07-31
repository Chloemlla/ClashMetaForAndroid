# CMFA ADB audit bridge

Run with PowerShell 7+ from an authorized Windows test workstation:

```powershell
.\Invoke-AdbAudit.ps1 -PackageName com.example.target -OutputDirectory .\audit-output -ConfirmAuthorizedUse
```

The output path is treated as a parent directory. The bridge creates a unique
`cmfa-audit-<timestamp>-<session>.zip` and never recursively deletes the path supplied by the
user. When more than one authorized device is connected, pass `-Serial <adb-serial>`; the
bridge refuses to guess. Each ADB command has a 120-second timeout by default; use
`-CommandTimeoutSeconds <5-600>` when a slower authorized device needs a different bound.

Optional inputs copy user-produced artifacts into the archive and register their SHA-256 hashes:

```powershell
.\Invoke-AdbAudit.ps1 -PackageName com.example.target -OutputDirectory .\audit-session `
  -ConfirmAuthorizedUse -AuthorizationReference "CASE-123" -Serial ABC123 `
  -PcapPath .\pcap\capture.pcapng -PcapMetadataPath .\pcap\metadata.json `
  -MitmLogPath .\mitm\events.jsonl -MitmJsonPath .\mitm\flows.json `
  -FridaLogPath .\frida\hooks.log -FridaJsonPath .\frida\hooks.json
```

Text records use the `Default` redaction mode, which masks known credentials, Android IDs,
device identifiers, email addresses, and precise coordinates. The selected ADB serial is
stored as a truncated SHA-256 pseudonym by default. External PCAP/proxy/hook files are copied
as user-reviewed raw artifacts and are labeled that way in the manifest. An unredacted text
export requires both `-RedactionMode None` and `-ConfirmUnredactedExport`.

`-ExternalArtifactDirectory` can import a reviewed directory of additional PCAPdroid/mitmproxy/Frida exports. The manifest records root and tcpdump availability separately from optional PCAPdroid, mitmproxy, and Frida inputs; every missing capability remains an evidence gap. The bridge never installs a CA, changes proxy/VPN settings, grants permissions, obtains root, or injects Frida. Configure and run those tools separately on an authorized test device, then provide their exported files.

Each session contains `manifest.json`, `records.jsonl`, and a standalone `report.jsonl`
(manifest first, then records). Import the ZIP in the Android app whenever external artifacts
are present so their SHA-256 hashes can be verified. The ZIP contract permits only those three
root files plus flat `artifacts/<name>` entries; nested or unexpected entries, duplicate paths,
missing hashes, oversized text, and archives above 64 MiB are rejected by the Android importer.
