# Profile File Round-Trip and Export Contract

## 1. Scope / Trigger

Use this contract when changing `FilesActivity` external editing/import, profile-file staging and validation, the read-only configuration outline, raw profile export, `FileProvider`, or Android backup rules.

Reference paths: `app/src/main/java/com/github/kr328/clash/ProfileFileActions.kt`, `app/src/main/java/com/github/kr328/clash/util/ProfileFileEditor.kt`, `app/src/main/java/com/github/kr328/clash/util/ProfileFileRoundTrip.kt`, `app/src/main/java/com/github/kr328/clash/util/ProfileFileExport.kt`, `service/src/main/java/com/github/kr328/clash/service/ProfileProcessor.kt`, and the three backup/FileProvider XML files under `app/src/main/res/xml/`.

Only `Profile.Type.File` configuration roots are editable. Other profile configurations remain read-only.

## 2. Signatures

```kotlin
companion object {
    suspend fun prepare(
        context: Context,
        original: Uri,
        editedSource: Uri = original,
    ): ProfileFileEditor
}

fun ProfileFileEditor.createEditIntent(context: Context): Intent
suspend fun ProfileFileEditor.hasChanges(): Boolean
suspend fun ProfileFileEditor.close()

suspend fun ProfileFileRoundTrip.validateAndStage(
    uuid: UUID,
    documentId: String,
    client: FilesClient,
    session: ProfileFileEditor,
): ProfileFileRoundTrip.Result

suspend fun IProfileManager.validate(uuid: UUID)
fun ConfigOutline.count(yaml: String): ConfigOutline.Counts
suspend fun ProfileFileExport.share(design: Design<*>, profile: Profile)
```

`validateAndStage` returns `Staged` or `Rejected(cause: Exception, rollbackFailure: Throwable?)`; cancellation is cleaned up and rethrown.

## 3. Contracts

- Never grant an external editor or share target direct filesystem access to imported/pending profile storage. Copy into a unique cache directory and expose only `content://` URIs from the non-exported `${applicationId}.fileprovider` with narrow temporary grants.
- Editing uses `cache/profile-editor/<session>/original.yaml` and `config.yaml`. Preserve a known-good original, use `.yaml`, prefer `application/yaml`, fall back to `text/plain`, attach `ClipData`, and grant read/write only to the edited cache URI.
- On return from `ACTION_EDIT`, compare the cache files. An unchanged result is not evidence that the editor saved elsewhere; show guidance to use the explicit **Import edited file** action. That action selects a candidate with `GetContent` and runs the same staging/validation path.
- Stage a changed candidate through `FilesClient.copyDocument`, which creates/uses the pending profile copy. Then call `withProfile { validate(uuid) }`; validation must cross `IProfileManager.validate` into `ProfileProcessor.validate` and the same kernel `Clash.fetchAndValid` path used by commit.
- Validation must not replace the imported profile, consume the pending edit, or claim the edit is active. Success means “staged”; the user still returns to the profile and explicitly saves/commits.
- Capture whether a pending edit existed before staging. On copy/validation failure, run rollback in `NonCancellable`: restore the session original when pending state pre-existed, otherwise `release(uuid)` to remove the newly-created pending clone. Keep the imported profile untouched.
- Cancellation is never swallowed: finish rollback, attach rollback failure as suppressed, then rethrow. Session deletion and kernel processing-directory cleanup are also non-cancellable. If rollback itself fails, surface the degraded state and require reopening Files before save.
- `ConfigOutline` is best-effort and read-only, not a validator. Scan at most 4 MiB and count only direct list items under top-level `proxies`, `proxy-groups`, and `rules`. Malformed, oversized, unreadable, or unexpected input returns an unavailable/friendly state and may still offer external open; it must never crash or authorize a write.
- Raw export begins only after the Profiles UI shows the sensitive-data warning and the user confirms. Copy `config.yaml` to `cache/profile-export/<session>/<sanitized-name>.yaml`, share through `FileProvider` with read-only permission and a chooser, and never expose a raw path. Clean the current directory if chooser launch fails and reap stale exports after 24 hours.
- F-17 remains a separate invariant: `full_backup_content.xml` and `data_extraction_rules.xml` include `sharedpref` only. Manual raw export must not be called from backup, device-transfer, receiver, worker, scheduled service, or automatic update paths; profile databases/files, subscription sources, configuration, and `ageSecretKey` remain outside automatic backup.

## 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| No compatible external editor | Report unavailable; stage nothing; delete session cache |
| Editor returns with unchanged cache file | Keep profile unchanged and explain explicit re-import |
| Explicitly imported candidate equals current config | Report no changes; do not create/modify pending state |
| Valid candidate, no prior pending edit | Create pending clone, kernel-validate, retain staged pending edit; imported profile stays unchanged |
| Invalid candidate, no prior pending edit | Non-cancellably release the new pending clone; keep imported profile |
| Invalid candidate with prior pending edit | Non-cancellably restore the pre-import pending `config.yaml` |
| Validation is cancelled | Complete rollback/cleanup, then rethrow cancellation |
| Rollback fails | Imported profile still remains; show rollback-failed guidance and require reopening Files |
| Outline is malformed, unreadable, or over 4 MiB | Show friendly unavailable state; do not throw |
| User cancels export confirmation | Create no export payload and launch no chooser |
| Chooser launch fails | Non-cancellably delete that export directory and report the error |
| Automatic backup/device transfer runs | Include shared preferences only; never include profile/export cache or profile storage |

## 5. Good / Base / Bad Cases

- Good: an editor writes the temporary YAML; it is copied to pending, kernel validation passes, and the UI says it is staged for an explicit save.
- Base: an editor cannot save back; the user chooses **Import edited file**, selects its saved copy, and receives the same validation/rollback guarantees.
- Good: malformed YAML outline returns a friendly unavailable message while external open remains possible.
- Good: the user confirms the sensitive warning, then shares a read-only FileProvider cache copy through the chooser.
- Bad: granting write access to the real document-provider profile and trusting editor return as validation.
- Bad: using `ConfigOutline.count` or a generic YAML parse as a substitute for `IProfileManager.validate`.
- Bad: committing before validation or allowing cancellation to interrupt rollback/cleanup.
- Bad: adding raw profiles, profile databases, exports, subscription URLs, or `ageSecretKey` to F-17 automatic backup.

## 6. Tests Required

- `ConfigOutline`: direct counts, nested-list exclusion, indentless lists, comments/BOM, malformed indentation, inline/unknown structures, and fail-soft behavior.
- Round-trip with fakes: copy occurs before validation; valid staging retains pending; invalid candidates restore prior pending state or release a new clone; rollback failure is reported; cancellation cleans up then rethrows.
- Kernel boundary: `IProfileManager.validate` delegates to `ProfileProcessor.validate`, uses `fetchAndValid`, leaves imported/pending ownership intact, and always removes processing scratch data.
- FileProvider intents: cache URI authority, `.yaml` name, MIME fallback, `ClipData`, exact read/write grants for edit, read-only grant for export, and no direct profile path.
- UI flow: unchanged editor result points to explicit re-import; export utility is reached only after positive sensitive-data confirmation.
- Static backup-rule test: both Android backup schemas include only `sharedpref` and never file/database/cache domains.

Actual Android/Gradle checks run only in GitHub Actions per repository policy.

## 7. Wrong vs Correct

### Wrong

```kotlin
startActivity(Intent(Intent.ACTION_EDIT, realProfileUri))
commit(uuid) // editor returned, so assume valid
```

This grants mutation of real profile state and bypasses kernel validation and rollback.

### Correct

```kotlin
val session = ProfileFileEditor.prepare(context, realProfileUri)
try {
    launch(session.createEditIntent(context))
    if (session.hasChanges()) {
        check(
            ProfileFileRoundTrip.validateAndStage(uuid, documentId, client, session) is
                ProfileFileRoundTrip.Result.Staged
        )
    }
} finally {
    session.close()
}
```

The user commits later; automatic backup remains independently restricted to shared preferences.
