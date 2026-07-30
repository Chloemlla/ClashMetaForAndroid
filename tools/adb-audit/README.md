# CMFA ADB audit bridge

Run from an authorized Windows test workstation:

```powershell
.\Invoke-AdbAudit.ps1 -PackageName com.example.target -OutputDirectory .\audit-session -ConfirmAuthorizedUse
```

Optional inputs copy user-produced artifacts into the archive and register their SHA-256 hashes:

```powershell
.\Invoke-AdbAudit.ps1 -PackageName com.example.target -OutputDirectory .\audit-session `
  -ConfirmAuthorizedUse -AuthorizationReference "CASE-123" `
  -PcapPath .\pcap\capture.pcapng -PcapMetadataPath .\pcap\metadata.json `
  -MitmLogPath .\mitm\events.jsonl -MitmJsonPath .\mitm\flows.json `
  -FridaLogPath .\frida\hooks.log -FridaJsonPath .\frida\hooks.json
```

`-ExternalArtifactDirectory` can import a reviewed directory of additional PCAPdroid/mitmproxy/Frida exports. The manifest records which adapters were present; missing adapters remain evidence gaps. The bridge never installs a CA, changes proxy/VPN settings, grants permissions, obtains root, or injects Frida. Configure and run those tools separately on an authorized test device, then provide their exported files.
