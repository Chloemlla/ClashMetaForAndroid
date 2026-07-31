[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'AuditPolicy.psm1') -Force

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

Assert-True (Test-AuditPackageName 'com.example.target') 'Expected a valid Android package name.'
Assert-True (-not (Test-AuditPackageName 'com.example;rm -rf')) 'Expected command-like package input to be rejected.'
Assert-True (-not (Test-AuditPackageName 'single')) 'Expected a single package segment to be rejected.'
Assert-True (-not (Test-AuditPackageName 'com.1invalid')) 'Expected a numeric package segment to be rejected.'

$sensitive = @'
Authorization: Bearer top-secret-token
https://example.test/path?token=query-secret&safe=value
password=plain-secret
 android_id: abcdef0123456789
device_serial=ABC123456
email=tester@example.test
latitude=31.2304 longitude=121.4737
'@
$redacted = Protect-AuditText $sensitive
foreach ($secret in @('top-secret-token', 'query-secret', 'plain-secret', 'abcdef0123456789', 'ABC123456', 'tester@example.test', '31.2304', '121.4737')) {
    Assert-True (-not $redacted.Contains($secret)) "Sensitive value was not redacted: $secret"
}
Assert-True ((Protect-AuditText $sensitive -Mode None) -eq $sensitive) 'Unredacted mode must preserve the source text.'
$metadata = ConvertTo-AuditMetadataText "case`nreference"
Assert-True ($metadata -eq 'case reference') 'Metadata text must not contain control characters.'

Write-Output 'ADB audit policy tests passed.'
