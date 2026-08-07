# Android Development Contracts

Concrete Android runtime, persistence, and UI contracts for this repository.

## Pre-Development Checklist

- Read the contract matching the state or lifecycle being changed.
- Identify the stable owner key before persisting profile-scoped state.
- Trace UI writes through service/runtime reads and deletion cleanup.
- Keep actual Gradle/build/test execution in GitHub Actions.

## Quality Check

- Scoped preferences do not leak between profiles.
- Legacy migration is deterministic and idempotent.
- Clone, cancel, delete, and active-profile transitions preserve or clean state intentionally.
- Authorized audit archives fail closed on consent, redaction, path, size, session, and hash violations.
- Scene automation remains opt-in and never starts or stops the VPN; success is recorded only after the full action succeeds.
- External profile edits are kernel-validated before staging, with non-cancellable rollback and sharedpref-only automatic backup.
- Focused pure tests cover preference invariants where local Android execution is prohibited.
- kotlinx-serialization is declared `implementation` in `:core`, so it is NOT transitively visible to `:app`. Any module that calls `Json.decodeFromString(...serializer())` must apply `id("kotlinx-serialization")` and add `libs.kotlin.serialization.json` itself; otherwise `:app:compile*Kotlin` fails with "Unresolved reference 'Json'" / "Cannot access class 'kotlinx.serialization.KSerializer'". See `app/build.gradle.kts` for the working example (AdblockHitsActivity parsing `adblock_hits.jsonl`).

## Contracts

- [Authorized ADB audit](./authorized-adb-audit.md)
- [Scene automation and failover](./scene-automation.md)
- [Profile file round-trip and export](./profile-file-round-trip.md)
- [Profile-scoped preferences](./profile-scoped-preferences.md)
- [ADB traffic capture](./adb-traffic-capture.md)
- [Runtime resilience](./runtime-resilience.md)
- [Update notes](./update-notes.md)
