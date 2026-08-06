# CI / Supply-chain Contracts

Operational and supply-chain contracts for GitHub Actions on this repository.

## Pre-Development Checklist

- Local Gradle/Go builds and dependency installs are forbidden — build/test execution lives in GitHub Actions.
- Before touching signing workflows, read the signing gate contract.
- Before touching the MetaCubeX Maven mirror or `maven-backup`, read the mirror `content` restriction.
- Never hand-edit `go.mod` version lines; go.sum regeneration is owned by the CI pipeline.
- Keystore material must never be added to the working directory.

## Quality Check

- No `secrets.KEYSTORE_*` reference is reachable from a `pull_request` event.
- `verify-repository-policy.py` passes on a clean tree (the only tolerated error is the pre-existing git-history `release.keystore` finding).
- `.gitmodules` has no `branch =` line; `update-dependencies.yaml` is the sole `--remote` consumer and is review-gated.
- The maven-backup mirror supplies only `com.github.kr328.golang` and `com.github.kr328.kaidl`, each behind `content { includeGroup(...) }`.

## Contracts

- [GitHub Actions auto-repair](./gh-action-auto-repair.md) — CI ops gate, gh-only repair loop
- [Supply-chain hardening](./supply-chain-hardening.md) — signing gate, keystore hygiene, submodule pinning, Go deps, Maven mirror restriction
