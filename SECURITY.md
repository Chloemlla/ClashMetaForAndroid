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

Release builds fail when the file or any required property is missing. The workflow verifies every APK with `apksigner` and compares its certificate SHA-256 digest with the protected `SIGNING_CERT_SHA256` secret before any version commit or tag is pushed. Temporary signing files are removed in an `always()` cleanup step.

Stable and alpha workflows both use `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Both workflows also require `SIGNING_CERT_SHA256`.

Never print signing properties, passwords, Base64 keystore values, or private-key material to workflow logs. Key rotation and distribution-console changes remain explicit maintainer operations outside this repository.
