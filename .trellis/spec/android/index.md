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
- Focused pure tests cover preference invariants where local Android execution is prohibited.

## Contracts

- [Profile-scoped preferences](./profile-scoped-preferences.md)
- [Update notes](./update-notes.md)
