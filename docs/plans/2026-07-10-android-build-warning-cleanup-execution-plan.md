# Android Build Warning Cleanup Execution Plan

## Frozen Requirement

`docs/requirements/2026-07-10-android-build-warning-cleanup.md`

## Internal Grade Decision

XL: the warning set has three independent root causes that can be investigated concurrently, followed by a single integration and review pass.

## Wave Plan

1. In parallel, inspect kaidl upstream/version compatibility, foreground-service compatibility options, and localized resource coverage.
2. Apply the narrowest source-level fixes without touching generated output.
3. Perform static verification only, review the complete diff, and clean runtime artifacts.
4. Commit and push the task changes, then observe the GitHub workflow without claiming an unobserved build pass.

## Ownership Boundaries

- Binder generation: `gradle/libs.versions.toml` or a narrowly scoped generator integration file only.
- Foreground services: shared compatibility utility and the two current service call sites if needed.
- Resources: default `design/src/main/res/values/strings.xml` only unless inspection proves another resource owner.
- Subagents investigate and report; the root agent owns final code edits, canonical requirement/plan files, commit, push, and completion claims.

## Verification Commands

- Focused `rg` searches for deprecated calls and missing resource defaults.
- PowerShell/.NET XML parsing of edited resource files.
- Dependency metadata and upstream source inspection without invoking Gradle.
- `git diff --check`, `git status --short`, and focused `git diff` review.
- Remote build/test authority: the GitHub workflow triggered by the pushed commit.

## Rollback Plan

Revert the single warning-cleanup commit if the GitHub workflow exposes incompatibility.

## Completion Language

Claim source cleanup only after static checks pass. Claim build success only if the corresponding GitHub Actions run is observed successful.

## Phase Cleanup

Remove temporary inspection files, keep only required governance receipts, stage only task-owned files, and ensure no live delegated work remains before delivery.
