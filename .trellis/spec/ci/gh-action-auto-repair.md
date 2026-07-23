# GitHub Actions auto-repair (Trellis CI ops)

Ops contract for repairing CI failures on **ClashMetaForAndroid** using **gh CLI only**.  
Local Gradle / Flutter builds are **forbidden** (device performance is insufficient; real builds run in GitHub workflows).

## Principles

| Rule | Detail |
| --- | --- |
| No local builds | Do **not** run `./gradlew`, Flutter, or full Android compiles on the agent host. |
| CI is source of truth | All compile/test/lint/package steps run in `.github/workflows/*`. |
| Declarative deps only | Document toolchain versions **from workflow YAML only** — never invent local toolchains. |
| gh for status/ops | Use `gh run` / `gh pr` for list, diagnose, watch, rerun, and PR checks. |
| Fix on branch | Land code/workflow fixes on a feature branch, push, then watch CI. |

## Scripts

| Script | Purpose |
| --- | --- |
| `.trellis/scripts/ci-status.ps1` | List latest 10 runs (status / conclusion / branch / url). Exit **1** if any recent **main** **push** run has `conclusion=failure` (cancelled does not fail the gate). |
| `.trellis/scripts/ci-diagnose.ps1` | Optional `-RunId`; if omitted, pick latest failure. Run `gh run view --log-failed`, write summary to `.trellis/workspace/ci-last-failure.md` (full log sibling `.log`). |
| `.trellis/scripts/ci-rerun-failed.ps1` | Required `-RunId`. Runs `gh run rerun --failed <id>`. |

### Usage

```powershell
# Status + main-push failure gate
.\.trellis\scripts\ci-status.ps1

# Diagnose latest failure (or pass -RunId)
.\.trellis\scripts\ci-diagnose.ps1
.\.trellis\scripts\ci-diagnose.ps1 -RunId 1234567890

# Rerun only failed jobs
.\.trellis\scripts\ci-rerun-failed.ps1 -RunId 1234567890

# Watch a run (native gh)
gh run watch 1234567890
```

Prerequisites: authenticated `gh` (`gh auth status`), repo context at workspace root.

## Parallel subagent roles

When auto-repairing a red CI, assign concurrent specialist roles:

| Role | Mission | Inputs | Outputs |
| --- | --- | --- | --- |
| **Forensics** | Map failing job/step to root cause from logs | `ci-last-failure.md` / `.log`, run URL | Failure class (code / workflow / secrets / flake), suspect paths |
| **Workflow audit** | Check YAML steps, concurrency, cache keys, action pins, permissions | `.github/workflows/*` | Diff proposals for workflow-only issues |
| **Code fix** | Minimal source/test/script change that addresses forensics finding | Repo tree, forensics note | Branch commit(s) — **no local Gradle** |
| **Verify** | Confirm green path after push | `gh run list/watch`, `gh pr checks` | Pass/fail gate; optional `ci-rerun-failed` for flakes |

Coordinate: forensics + workflow audit first (or in parallel); code fix only after a clear hypothesis; verify last.

## Gate key (repair loop)

1. **Branch** — create/update feature branch (never force-push main from agents unless parent policy says otherwise).
2. **Fix** — edit code and/or workflow; keep changes minimal and attributable.
3. **Push** — `git push -u origin HEAD` (or existing remote branch).
4. **Watch** — `gh run watch <run_id>` **or** poll with `.trellis/scripts/ci-status.ps1`.
5. **Diagnose if red** — `.trellis/scripts/ci-diagnose.ps1 -RunId <id>` → re-enter forensics.
6. **Flake path** — `.trellis/scripts/ci-rerun-failed.ps1 -RunId <id>` only when logs show transient infra/network, not deterministic product bugs.

## PR checks

```powershell
# After opening / updating a PR
gh pr view --json number,url,state,statusCheckRollup,headRefName
gh pr checks
gh pr checks --watch   # optional blocking watch
```

Use `gh pr view` for rollup metadata and `gh pr checks` for per-check conclusions. Do not claim “green” without check evidence.

## Declarative toolchain (from workflows only)

Documented from `.github/workflows/` as of this spec — **do not** run local installs to “validate” these; CI images apply them.

| Tool | Declared value | Where |
| --- | --- | --- |
| Java / JDK | **Temurin 21** (`actions/setup-java@v5`, `distribution: temurin`, `java-version: 21`) | `build-debug.yaml`, `build-pre-release.yaml`, `build-release.yaml`, `update-dependencies.yaml` |
| Go | **1.26** via MetaCubeX build feed (`actions/setup-go@…`, `go-version: '1.26'`, `go-download-base-url: https://github.com/MetaCubeX/go/releases/download/build`) | same set |
| Runner | `ubuntu-latest` | workflow `runs-on` |
| Gradle | `gradle/actions/setup-gradle@v5` + wrapper validation (`gradle/actions/wrapper-validation@v5` on debug) | build workflows |
| Checkout | `actions/checkout@v6` | workflows |

If YAML drifts, **trust the workflow file** and update this table — never invent a local JDK/Go version for “parity builds.”

## End-to-end ops flow

```text
ci-status.ps1
    │
    ├─ gate OK  → done / continue feature work
    │
    └─ gate FAIL / known red run
           │
           ▼
    ci-diagnose.ps1 [-RunId]
           │
           ▼
    .trellis/workspace/ci-last-failure.md
           │
           ├─► Forensics ──► root cause
           ├─► Workflow audit ──► YAML-only fix?
           └─► Code fix on branch ──► push
                      │
                      ▼
              gh run watch | ci-status.ps1
                      │
              ┌───────┴────────┐
              ▼                ▼
           green             still red
              │                │
              ▼                ├─ deterministic → diagnose + fix again
         gh pr checks          └─ flake → ci-rerun-failed.ps1
```

## Explicit non-goals

- Running Gradle/Flutter on the developer or agent machine.
- Writing a “super file” dump of the whole repo.
- Committing/pushing from these scripts (parent integration owns git history).
- Changing secrets or org policy outside documented workflow permissions.

## Related paths

- Workflows: `.github/workflows/`
- CI helper scripts (in-repo Python/bash used **by** Actions, not local builds): `.github/scripts/`
- Trellis workspace artifacts: `.trellis/workspace/`
- Product Trellis notes: `.trellis/workflow.md`
