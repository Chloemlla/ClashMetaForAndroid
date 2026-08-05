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

## Contracts

- [Authorized ADB audit](./authorized-adb-audit.md)
- [Scene automation and failover](./scene-automation.md)
- [Profile file round-trip and export](./profile-file-round-trip.md)
- [Profile-scoped preferences](./profile-scoped-preferences.md)
- [ADB traffic capture](./adb-traffic-capture.md)
- [Runtime resilience](./runtime-resilience.md)
- [Update notes](./update-notes.md)
