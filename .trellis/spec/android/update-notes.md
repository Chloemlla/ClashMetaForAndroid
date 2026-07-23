# Android Update Notes (immersive post-update tutorial)

> Status: active · Gate: once per `versionCode|commitHash` · Assets: `design/src/main/res/raw/update_notes.json`

## Purpose

When the user installs a **new build** (commit hash / version code change), show an immersive full-screen tutorial that explains this release — parallel to the first-install open-source notice (GPL, third-party credits, module notes).

## Build identity

| Field | Source | Role |
|-------|--------|------|
| `versionName` / `versionCode` | `build.gradle.kts` | User-facing version |
| `COMMIT_HASH` | `BuildConfig` from `git rev-parse` / `GITHUB_SHA` | GitHub commit short hash |
| `BUILD_TIME` | `BuildConfig` from `Instant.now()` / `SOURCE_DATE_EPOCH` / `BUILD_TIME` env | Package build timestamp |
| Gate key | `"$versionCode|$commitHash"` stored in `AppStore.lastSeenBuildIdentity` | Show notes only when key changes |

## Lifecycle

1. **First install** → `OpenSourceNoticeActivity` (hard gate). On accept, seed `lastSeenBuildIdentity` (no update notes).
2. **Later update** (gate key differs) → `UpdateNotesActivity` (immersive). On accept, write new gate key.
3. Same build relaunch → skip.

## Content pipeline

1. Curated **highlights** + **modules** in `update_notes.json` (human-edited product notes).
2. **commits** array generated from `git log` (hash, subject, conventional type) via:
   - `.trellis/scripts/generate-update-notes.ps1` (local / agent)
   - CI may re-run the same script before assemble
3. Runtime loads assets JSON and overlays live `BuildConfig` identity for the header.

## Schema (`update_notes.json`)

```json
{
  "schemaVersion": 1,
  "identity": {
    "commitHash": "849aafe",
    "buildTime": "2026-07-23T20:30:00Z",
    "versionName": "2.11.32",
    "versionCode": 211032
  },
  "highlights": [{ "id": "...", "title": "...", "body": "..." }],
  "commits": [{ "hash": "...", "subject": "...", "type": "feat|fix|merge|docs|ci|chore" }],
  "modules": [{ "module": ":service", "summary": "..." }]
}
```

## UI surface

- `UpdateNotesDesign` + shared settings chrome (`DesignSettingsCommonBinding`) for consistency with open-source notice.
- Sections: intro · build identity · highlights · branch modules · recent commits/merges · continue.
- User-facing copy only (no internal task/phase labels) — Trellis frontend copy rule.

## Code map

| Layer | Path |
|-------|------|
| Activity | `app/.../UpdateNotesActivity.kt` |
| Design | `design/.../UpdateNotesDesign.kt` |
| Model | `design/.../model/UpdateNotes.kt` |
| Gate | `MainActivity.ensureUpdateNotesAcknowledged` |
| Store | `AppStore.lastSeenBuildIdentity` |
| Asset | `design/src/main/res/raw/update_notes.json` |
| BuildConfig | `COMMIT_HASH`, `BUILD_TIME` in root `build.gradle.kts` |

## Agent checklist

- [ ] Bump curated highlights when shipping user-visible features
- [ ] Run `generate-update-notes.ps1` so commit list matches HEAD
- [ ] Commit `update_notes.json` with the feature
- [ ] Verify gate: first install skips notes; second build with new hash shows page
