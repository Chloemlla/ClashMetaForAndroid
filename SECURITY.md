# Security and Release Signing

## Signing-key incident note

`release.keystore` was previously committed to this repository. It has been removed from the current tree and purged from rewritten Git history on this fork. Existing forks, mirrors, local clones, CI caches, and third-party archives created before the purge may still retain the blob.

That historical exposure is irreversible for any copy that already left the repository. Maintainers must assume the leaked container is public, determine whether it ever signed a production build, and rotate or revoke the corresponding certificate through the applicable distribution channel (preferably Play App Signing or another managed signing service). Do not reuse a historically committed keystore for new production builds.

## CI signing contract

Release jobs must provide the keystore only through GitHub Actions secrets, decode it under `$RUNNER_TEMP`, and create an untracked `signing.properties` containing:

```properties
keystore.file=/absolute/temporary/path/release.keystore
keystore.password=...
key.alias=...
key.password=...
```

Release builds fail when the file or any required property is missing. Temporary signing files are removed in an `always()` cleanup step. Release artifacts also get a `SHA256SUMS` file generated after the APKs are built.

Stable and alpha workflows both use `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

Never print signing properties, passwords, Base64 keystore values, or private-key material to workflow logs. Key rotation and distribution-console changes remain explicit maintainer operations outside this repository.

## Authorized device audit boundary

The Windows ADB audit companion is for devices and packages the tester is authorized to
inspect. It requires explicit authorized-use confirmation, refuses ambiguous multi-device
selection, validates the package name, and writes into a unique child directory under the
user-selected output location. It must never install a CA, change the system proxy or VPN,
grant permissions, obtain root, bypass TLS pinning, or inject a process.

Generated text records are redacted by default. Disabling redaction requires a second
explicit confirmation, and externally supplied PCAP/mitmproxy/Frida artifacts remain raw
user-reviewed inputs. The Android importer rejects traversal paths, oversized reports,
missing consent/redaction metadata, mismatched sessions, and unhashed or hash-mismatched
artifacts.

## Working-directory keystore hygiene

The signing keystore must never be placed in the repository working directory, even if
covered by `.gitignore`. Physical presence on disk exposes the private-key material to
anyone with working-copy access (developers, CI runners, backups, screen-sharing, sync).

On 2026-08-06 the following files were found in the repo root and moved to
`F:\Repositories\GitHub\clashmeta-keystore-backup\`:

- `clashmeta-release.jks` (JKS keystore, 2750 bytes)
- `keystore_base64.txt` (Base64-encoded PKCS#12, 3668 bytes)

These files were gitignored but physically present. They must be **rotated** before any
new production signing — see "Signing-key incident note" above. The
`setup-android-signing.ps1` script has been updated to write Base64 output to a
randomized temporary path under `$env:TEMP` instead of the repo directory.
