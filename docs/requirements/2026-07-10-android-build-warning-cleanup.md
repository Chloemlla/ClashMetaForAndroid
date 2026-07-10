# Android Build Warning Cleanup

## Goal

Remove the reported Android/Kotlin build warnings at their source without editing generated output.

## Deliverable

- Make kaidl-generated Binder code avoid the deprecated untyped `Parcel.readSerializable()` API.
- Replace deprecated foreground-service shutdown calls with an API 21-compatible non-deprecated path.
- Provide default resources for the nine strings that currently exist only in localized resource sets.

## Constraints

- Do not edit files under `build/generated`.
- Do not run Flutter, Gradle, or local build/test commands.
- Actual build verification must run in GitHub Actions after push.
- Preserve unrelated user changes and avoid broad dependency upgrades or unrelated refactors.
- Commit and push the completed source changes; GPG signing may be omitted if necessary.

## Acceptance Criteria

- Generated `IProfileManager` code no longer emits deprecation warnings for untyped `readSerializable()` calls.
- No source file retains `stopForeground(true)`.
- `disable_sniffer`, `port_whitelist`, `script_mode`, `sideload_geoip`, `sideload_geoip_summary`, `sniff`, `sniffer_config`, `sniffer_override`, and `sniffing` all have default values.
- Static review shows no malformed XML, whitespace errors, or unintended files.
- The pushed GitHub workflow is the sole build/test authority and its observed status is reported accurately.

## Non-Goals

- Reworking Binder interfaces or profile behavior.
- Updating unrelated AndroidX, Kotlin, Room, or Android Gradle Plugin dependencies.
- Rewriting translations or removing localized resources.

## Autonomy Mode

Implementation-forward. The warning messages identify the affected APIs and resource names precisely, so no product decision is required.

## Assumptions

- A newer compatible kaidl release or a narrow generator-side source fix is available for typed serializable reads.
- Removing a foreground notification on service teardown preserves current behavior.
- English fallback labels matching the existing localized meanings are acceptable defaults.

## Verification Contract

Local verification is static only: focused searches, XML parsing, dependency/source inspection, `git diff --check`, and diff review. Compilation and tests are deferred to GitHub Actions.
