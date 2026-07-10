# Proxy Group Progressive Delay Results

## Summary

Improve the Android proxy-group delay-test experience so completed node results appear immediately instead of waiting for the entire group.

## Goal

Keep the existing concurrent mihomo health check while progressively reflecting its per-node results in the proxy list.

## Deliverable

- Refresh the active proxy group every 100 ms while its delay test is running.
- Update only changed proxy rows and animate delay changes without flashing the whole page.
- Keep the group-level testing indicator active until the health check and final refresh finish.

## Constraints

- Do not run Flutter, Gradle, or local build/test commands.
- Actual build verification must run through the GitHub workflow after push.
- Preserve the user's existing `.gitignore` and `AGENTS.md` workspace changes.
- Do not change mihomo's health-check algorithm or Binder interface.

## Acceptance Criteria

- A node whose health check finishes early displays its new delay before slower nodes finish.
- Polling occurs at 100 ms intervals only while the requested group is testing.
- Delay text fades and rises into place, and delay-sort position changes use RecyclerView move animations.
- The delay-test spinner remains visible until the final group state is rendered.
- Only proxy rows whose data or position changed are rebound.

## Primary Objective

Reduce perceived waiting time by making proxy delay-test progress visible per node.

## Non-Objective Proxy Signals

- Merely making the spinner faster.
- Refreshing the entire proxy page every 100 ms.
- Changing the configured health-check timeout or concurrency limit.

## Validation Material Role

Static diff review proves the update flow; the pushed GitHub Build Debug workflow is the actual build verification.

## Anti-Proxy-Goal-Drift Tier

Narrow product UX change.

## Intended Scope

Proxy activity orchestration and proxy-list rendering in the app and design modules.

## Abstraction Layer Target

Activity polling, RecyclerView data updates, and custom view drawing state.

## Completion State

Code committed and pushed, with GitHub workflow status reported accurately.

## Generalization Evidence Bundle

The implementation reuses the existing generic list diff utility and the existing custom-view frame invalidation loop.

## Non-Goals

- Adding a new native callback API.
- Replacing mihomo health checks.
- Changing delay colors, timeout rules, or proxy selection behavior.

## Autonomy Mode

Implementation-forward with inferred details because the requested behavior and refresh interval are explicit.

## Assumptions

- Proxy names are stable unique identities within a proxy group.
- A changed delay value is the UI-visible signal that a node's latest test result is available.
- The existing mihomo provider health check remains the source of concurrency.

## Evidence Inputs

- `ProxyActivity` currently reloads only after `healthCheck()` returns.
- The pinned mihomo implementation writes each proxy's delay history when its individual `URLTest` completes.
- The pinned provider health check runs node tests through an errgroup with a concurrency limit of 10.

## Scope Amendment: Release Workflows

The same delivery also replaces the deprecated pre-release/release publishing chain with the pinned `softprops/action-gh-release` action.

- Pre-release tags must be unique per commit using the app version and seven-character commit hash.
- Formal releases must use the manually supplied release tag.
- Both workflows must grant `contents: write` to `GITHUB_TOKEN`.
- The old delete-release, forced-tag-update, and unused changelog-builder steps must be removed.
