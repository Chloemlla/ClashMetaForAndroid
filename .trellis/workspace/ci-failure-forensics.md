# CI failure forensics (gh CLI only)

Generated: 2026-07-24 (Asia/Shanghai)  
Method: `gh run list` + `gh run view <id> --log-failed` + `git`/workspace symbol search  
Local Gradle/Flutter: **not run** (repo policy)

Current `main` HEAD: `b651595eec9078064b144c2c482b331e8e099c55`  
`fix(ci): repair AAPT update_notes_title and codify gh auto-repair in Trellis`

---

## 1. Recent runs snapshot (`gh run list --limit 30`)

| conclusion | count (approx in window) | notes |
|------------|--------------------------|--------|
| failure | many | mix of main + feature/local-subscription-traffic |
| cancelled | many | superseded by newer pushes on main |
| success | several | last clean main success before update-notes wave: `30001901020` |

Most recent failures examined below (actual `conclusion=failure`, newest first).  
Also noted: run `30042449291` (same AAPT root cause as `30042272415`, on `de0e6f3`).

---

## 2. Most recent 3 actual failures

### Failure A — OPEN at run time / FIXED on current HEAD

| Field | Value |
|-------|-------|
| **run id** | `30042272415` |
| **url** | https://github.com/Chloemlla/ClashMetaForAndroid/actions/runs/30042272415 |
| **branch** | `main` |
| **headSha** | `b5f6dbe1556564bd087e875b204e08e06e99a91f` |
| **title** | chore(docs): refresh update_notes.json for HEAD identity |
| **workflow** | Build Release on Push → job **Verify** → step **JVM unit tests** |
| **failing task** | `:app:mergeAlphaDebugResources` |
| **root cause class** | **AAPT / resource compile** (not secrets, not signing) |
| **root cause detail** | `string/update_notes_title` failed: *Invalid unicode escape sequence in string* → *does not contain a valid string resource*. Intermediate path reported under `service/.../values.xml:121` (merge intermediate; **source of truth is** `design/src/main/res/values/strings.xml`). Literal content at fail SHA: `What's New` — unescaped `'` is illegal in Android XML string resources and is reported by aapt2 as an invalid unicode escape. |
| **fixed already on current main HEAD?** | **yes** — `b651595` rewrites title to `What is New` (no apostrophe) |
| **CI verified on fix commit?** | **not yet** — `gh run list --commit b651595` returned empty at forensics time |

**Sibling same-cause run (not counted as separate root cause):**  
`30042449291` — `feat(settings): add PiliPlus VPN auto-adapt toggle` @ `de0e6f3` — identical AAPT `update_notes_title` failure (pre-fix commit).

### Failure B — HISTORICAL

| Field | Value |
|-------|-------|
| **run id** | `29938391226` |
| **url** | https://github.com/Chloemlla/ClashMetaForAndroid/actions/runs/29938391226 |
| **branch** | `feature/local-subscription-traffic` |
| **headSha** | `c87f400585cef8dae5d7e7150c405d8341718605` |
| **title** | fix(build): make sideload APKs installable (INSTALL -15) |
| **workflow** | Build Release on Push → **MetaRelease** + **AlphaPreRelease** |
| **failing tasks** | `:app:compileMetaReleaseKotlin` / `:app:compileAlphaReleaseKotlin` |
| **root cause class** | **Kotlin compile error** |
| **root cause detail** | `MainApplication.kt:79 Unresolved reference 'installSafely'`; also `MainApplication.kt:71 Cannot infer type for this parameter`. Host called SDK API `installSafely()` not present on published `lumen-crash` AAR (only `LumenCrash.install(...)`). |
| **fixed already on current main HEAD?** | **yes** — current `MainApplication.kt` uses `LumenCrash.install(...)` inside `runCatching { ... }`; `installSafely` appears only in a comment. Fix lineage includes `07b824c` / `f763fb2` (*stop cold-start flash-exit and restore LumenCrash capture*). |
| **recommended code fix if open** | n/a |

### Failure C — HISTORICAL (same compile family as B)

| Field | Value |
|-------|-------|
| **run id** | `29937463359` |
| **url** | https://github.com/Chloemlla/ClashMetaForAndroid/actions/runs/29937463359 |
| **branch** | `feature/local-subscription-traffic` |
| **headSha** | `fe22dbb0dad7e98a5ad7104fef7d00006e569d07` |
| **title** | fix(app): prevent cold-start crash from LumenCrash and dashboard fetch |
| **workflow** | Build Release on Push → MetaRelease + AlphaPreRelease |
| **failing tasks** | same Kotlin compile tasks as B |
| **root cause class** | **Kotlin compile error** (same symbols) |
| **root cause detail** | Identical: `Unresolved reference 'installSafely'` + type inference failure at `MainApplication.kt:71/79`. |
| **fixed already on current main HEAD?** | **yes** (same as B) |
| **recommended code fix if open** | n/a |

---

## 3. Presence check on current `main` HEAD (`b651595`)

| Symbol / file | Status on HEAD |
|---------------|----------------|
| `design/.../values/strings.xml` → `update_notes_title` | `What is New` (safe; no bare `'`) |
| `What's New` apostrophe form | **gone** from HEAD tree |
| `installSafely` call site in `*.kt` | **none** (comment only) |
| `LumenCrash.install` + `runCatching` | **present** in `MainApplication.kt` |

**Open failure causes on current main code:** **none** among the three analyzed failures.

Residual risk: fix commit `b651595` not yet observed in Actions runs; first push CI after this commit is the verification gate.

---

## 4. Open vs historical summary

### Still open on current main code
- **None** (AAPT apostrophe fixed in tree; LumenCrash API mismatch fixed earlier).

### Historical (resolved on main)
- **AAPT `update_notes_title` / unescaped `'`** — runs `30042272415`, `30042449291` (and any concurrent cancelled supersedes of the update-notes landing). Fixed by `b651595`.
- **`installSafely` unresolved + type inference** — runs `29938391226`, `29937463359` (and earlier lumen-crash integration failures on feature/main). Fixed by later MainApplication host integration using published `install` API.
- **Older lumen-crash version resolver / JSON parse** (seen in earlier failed titles) — treated as historical; later main success `29936821247` / `30001901020` window indicates resolver path recovered.

### Cancelled noise
- Multiple main pushes after merge cascade cancelled prior *Build Release on Push* runs; not root-cause failures.

---

## 5. Patch plan (only if still open)

**Status: no still-open failure cause on current main HEAD — patch plan not required.**

If a future CI run still fails AAPT on the same resource, apply:

1. Open `design/src/main/res/values/strings.xml`.
2. Ensure `update_notes_title` has **no unescaped apostrophe**. Preferred forms:
   - `What is New` (current), or
   - `What\'s New`, or
   - `"What's New"` (double-quoted string content).
3. Grep all `strings.xml` for bare `'` inside `<string>` bodies (exclude CDATA / already-escaped `\'`).
4. Do **not** local-Gradle; push and rely on GitHub Actions Verify (`:app:mergeAlphaDebugResources` + unit tests).
5. Optional: prefer `\'` over rewording so product copy can keep “What's New”.

If `installSafely` regresses:

1. Keep host on `LumenCrash.install(...)` only until AAR publishes `installSafely`.
2. Always wrap install in `runCatching` so cold start cannot hard-crash.

---

## 6. Commands used

```text
gh run list --limit 30 --json databaseId,status,conclusion,name,headBranch,displayTitle,url,createdAt
gh run view 30042272415 --log-failed
gh run view 30042449291 --log-failed
gh run view 29938391226 --log-failed
gh run view 29937463359 --log-failed
git grep / git show for update_notes_title + installSafely on HEAD vs fail SHAs
```
