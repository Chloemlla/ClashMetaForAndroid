# CMFA ADB audit bridge

Run from an authorized Windows test workstation:

```powershell
.\Invoke-AdbAudit.ps1 -PackageName com.example.target -OutputDirectory .\audit-session
```

Optional `-PcapPath`, `-MitmLogPath`, and `-FridaLogPath` copy user-produced artifacts into the archive. The script never installs a CA, changes proxy/VPN settings, grants permissions, obtains root, or injects Frida. Missing capabilities are recorded as limitations. Review and redact `manifest.json`/`records.jsonl` before sharing.
