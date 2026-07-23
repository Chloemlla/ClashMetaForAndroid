# ClashMetaForAndroid — Trellis workflow (subset)

## Specs

| Spec | Path |
|------|------|
| Update notes gate | `spec/android/update-notes.md` |
| **CI auto-repair (gh + subagents)** | `spec/ci/gh-action-auto-repair.md` |

## CI control plane

- **Forbidden:** local Gradle / Flutter / host APK builds.
- **Allowed:** code edits; verification only via GitHub Actions.
- **Tools:** `gh run list|view|watch|rerun`, `gh pr checks`.
- **Helpers:**
  - `scripts/ci-status.ps1` — latest runs + main-push failure gate
  - `scripts/ci-diagnose.ps1` — dump failed logs → `workspace/ci-last-failure.md`
  - `scripts/ci-rerun-failed.ps1` — `gh run rerun --failed`
  - `scripts/generate-update-notes.ps1` — package release notes JSON

## Parallel subagents

When repairing Actions/PRs, parent may spawn concurrent roles:

1. **Forensics** — gh log-failed → `workspace/ci-failure-forensics.md`
2. **Workflow audit** — YAML/script hardening
3. **Code fix** — source/string/policy fixes (no local assemble)
4. **Verify** — push and poll Actions until green

See `spec/ci/gh-action-auto-repair.md` for the full loop.
