# Supply-chain hardening (signing / submodule / deps / Maven mirror)

Executable contracts for the 2026-08-06 batch-1 supply-chain fixes (audit STOP-A/B/F/G/H, task `08-06-supply-chain-fixes-batch1`).
Keep actual build/test execution in GitHub Actions; local builds/deps are forbidden per project rule.

## 1. Scope / Trigger

- Trigger: 网安审计发现 PR 事件用生产密钥签名、签名材料物理在磁盘、子模块浮动 Alpha、Go 依赖 CVE、第三方 Maven 镜像无完整性校验。

## 2. Signatures — signing gate (`.github/workflows/build-debug.yaml`)

- The workflow is split into two jobs so PR events never hold a `contents:write` token:
  - `BuildDebug` (validation job, runs on `pull_request` **and** `workflow_dispatch`): `permissions: { contents: read, packages: read }`. Builds unsigned output only — debug / unit / lint / negative test `Release signing must fail without credentials` / policy checks / ADB audit.
  - `SignAndPublish` (signing/publishing job): `permissions: { contents: write, packages: read }`, `needs: BuildDebug`, and a **job-level** triple guard:
    ```yaml
    if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.repository_owner == 'Chloemlla'
    ```
    Because the guard is at job level, `pull_request` events never start this job, so the `contents:write` token is never granted on PRs. The job re-runs the checkout/setup/patches/CA/lumen-crash preamble (separate runner, no workspace sharing) then signs/stages/uploads/publishes.
- Any step that reads `secrets.KEYSTORE_*` or stages/uploads a **signed** APK lives **only** in `SignAndPublish`; no per-step `if` is needed there because the job is already gated (the `Publish Alpha pre-release` step relies on the job-level `if`, not its own).
- PR runs (`pull_request`) build **unsigned** output only (debug / unit / lint / negative test `Release signing must fail without credentials`).
- `Remove signing credentials` cleanup keeps `if: always()` in `SignAndPublish`.
- Affected steps (all moved into `SignAndPublish`): `Prepare release signing`, `Build signed Alpha release APK`, `Stage secret-signed Alpha APKs`, `Prepare Alpha migration APK aliases`, `Upload APKs`, `Publish Alpha pre-release`.
- Verify: `grep -n "KEYSTORE_" .github/workflows/build-debug.yaml` — every hit must be inside `SignAndPublish`.

## 3. Keystore hygiene contract

- **Never** place keystore material in the repo working directory, even if gitignored.
- `setup-android-signing.ps1` writes base64 to `$env:TEMP\cmfa-signing-<random>\keystore_base64.txt` (randomized dir), offers cleanup, and never writes `keystore_base64.txt` into the repo root.
- Keystore backup (outside repo): `F:\Repositories\GitHub\clashmeta-keystore-backup\`.
- Rotation of any keystore that touched disk is a **maintainer action**, not an automated fix.

## 4. Policy checks (`.github/scripts/verify-repository-policy.py`)

| Condition | Result |
| --- | --- |
| `keystore_base64.txt` or any `*.jks` / `*.p12` / `*.keystore` file anywhere under ROOT, except dir `.git` only | `require(False, ...)` → build fails |
| `.gitmodules` contains a `branch =` line (matched as regex `(?m)^\s*branch\s*=`, so `branch=`, `branch\t=`, and leading-whitespace forms are also caught) | `require(False, ...)` → build fails |

Exclusion prunes **subtrees**: `exclude_dirs.intersection(found.relative_to(ROOT).parts)`.
`exclude_dirs` is `{".git"}` only — `.gradle`, `build`, `tmp`, `.trellis` are deliberately NOT exempt, since a keystore under `build/` or `tmp/` is still a policy violation. Only `.git` (git objects/packed refs) is excluded from the scan.
Do NOT use `if found.is_dir() and found.name in exclude_dirs: continue` — `Path.rglob("*")` still yields children of the excluded dir (traverses `.git/`, defeats exclusion).

## 5. Submodule & Go dependency contracts

- `.gitmodules`: no `branch =` line — submodules pinned to recorded commits.
- **Submodule↔go.mod consistency** (2026-08-06): the mihomo submodule must be pinned to a commit whose `go.mod` matches `core/src/main/golang/go.mod` dep-for-dep. Canonical reference: the gitlink used by `MetaCubeX/ClashMetaForAndroid@main` (`gh api repos/MetaCubeX/ClashMetaForAndroid/git/trees/main?recursive=1`). Pinning a NEWER mihomo head breaks the go build with `go: updates to go.mod needed; go mod tidy` because new library deps (e.g. `metacubex/mipstack`, `metacubex/zerotier-go`) are missing from the main go.mod. Do NOT fix this by hand-editing go.mod — re-pin the submodule instead, or regenerate via the update-dependencies workflow only. `go.uber.org/automaxprocs` may be required by mihomo's go.mod yet absent from CMFA's: that is expected, since CMFA builds mihomo as a library (its `main` package, the only automaxprocs importer, is never built).
- `update-dependencies.yaml` is the ONLY workflow that may run `git submodule update --remote`; the update PR body must carry the new submodule commit hash for review (step `Record submodule commit for review`, `body-path: /tmp/pr-body.md`), and must never auto-merge.
- Go security bump runs in CI (never locally): for both `core/src/main/golang` and `core/src/foss/golang`:
  ```bash
  go get -u golang.org/x/net golang.org/x/crypto golang.org/x/sys && go mod tidy
  ```
  Path math: from workspace root, `cd core/src/main/golang` then `cd ../../foss/golang` → `core/src/foss/golang` (correct).
- go.sum is regenerated **in CI only**. Do NOT hand-edit `go.mod` version lines — local go.sum regeneration is impossible under the no-local-deps rule; a hand-edit desyncs the build.
- This bump resolves: CVE-2025-22872 (x/net), CVE-2024-45338, CVE-2023-45288, CVE-2024-45337/45336 (x/crypto).
- **Pre-PR consistency gate** (2026-08-07): `update-dependencies.yaml` now runs a `Verify submodule go.mod consistency` step between `Bump Go security deps` and `Create Pull Request`. It parses the require blocks of `core/src/foss/golang/clash/go.mod` (submodule) and `core/src/main/golang/go.mod` (main) and fails the job if any module path appears in both with different versions (a conflict `update-go-mod-replace` should have aligned). Absent-from-main deps are NOT flagged (mihomo main-package-only importers like `go.uber.org/automaxprocs` are expected to be missing per the contract above). This catches a newer mihomo head whose go.mod desyncs the build before a PR is opened.

## 6. Maven mirror contract (STOP-G)

- MetaCubeX `raw.githubusercontent.com/MetaCubeX/maven-backup` provides ONLY groups:
  - `com.github.kr328.golang` (build plugin)
  - `com.github.kr328.kaidl` (kaidl IPC framework)
- `build.gradle.kts` (buildscript + subprojects) and `kaidl-compiler-patch/settings.gradle.kts` declare the mirror with:
  ```kotlin
  maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases") {
      content {
          includeGroup("com.github.kr328.golang")
          includeGroup("com.github.kr328.kaidl")
      }
  }
  ```
  `kaidl-compiler-patch` restricts to `com.github.kr328.kaidl` only.
- Never add un-restricted mirror entries for new groups; migrate artifacts to a controlled repo (GitHub Packages / Sonatype, signed) when possible.

## 7. Validation & Error Matrix

| Condition | Behavior |
| --- | --- |
| PR event reaches a signing step | Step skipped (guard false); unsigned artifacts only |
| Keystore file appears anywhere in working tree | `verify-repository-policy.py` fails the build |
| `.gitmodules` gets a `branch =` | Policy build fails |
| update-dependencies PR without submodule hash | Never happens: body-path carries it |
| New artifact group resolves from maven-backup | Resolution fails at Gradle time unless `content` includes it |

## 8. Wrong vs Correct

#### Wrong
```yaml
# PR events sign with production secrets
- name: Prepare release signing
  env: { KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }} }  # no if-guard
```
```python
# does NOT prune subtrees — rglob still traverses .git/ and excluded dirs
if found.is_dir() and found.name in exclude_dirs:
    continue
```
```bash
# hand-edit go.mod version without regenerating go.sum
# sed -i 's|golang.org/x/net v0.35.0|golang.org/x/net v0.36.0|' go.mod
```
```ini
# .gitmodules floating branch
[submodule "clash-foss"]
	branch = Alpha
```

#### Correct
```yaml
- name: Prepare release signing
  if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.repository_owner == 'Chloemlla'
  env: { KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }} }
```
```python
if exclude_dirs.intersection(found.relative_to(ROOT).parts):
    continue
```
```yaml
# update-dependencies.yaml — CI-owned regeneration
cd core/src/main/golang && go get -u golang.org/x/net golang.org/x/crypto golang.org/x/sys && go mod tidy
```
```ini
[submodule "clash-foss"]
	path = core/src/foss/golang/clash
	url = https://github.com/MetaCubeX/mihomo
```

## 9. Known open items (2026-08-06)

- Keystore **rotation** still required (material was on disk; distribution channel decision is the user's).
- `setup-android-signing.ps1` still generates the raw `clashmeta-release.jks` in the repo root by design (signing.properties/CI coordination cost) — tension with the "never in working dir" rule, unresolved.
- `build-pre-release.yaml` signs on `push` (not PR), guarded by `github.repository_owner` — lower risk; unify guard style in a later batch.
- `build-pre-release.yaml` / `build-release.yaml` do not run the Go security bump — releases before the update PR merges still carry vulnerable Go deps.
- `setup-go` uses the MetaCubeX Go toolchain feed (NET-SUPPLY-7) — later batch.
- `release.keystore` still reachable from git history (pre-existing policy failure; history rewrite out of scope).
- `ageSecretKey` is stored **plaintext** in Room (`imported.ageSecretKey` / `pending.ageSecretKey`, TEXT columns). The `fromSecureString`/`toSecureString` `String?→String?` `@TypeConverter`s added in `e91fbe38` were **dead code that never executed** (Room flags custom String↔String converters as conflicting and String columns map natively) and broke the KSP build — they were removed on 2026-08-06 to unblock CI. A real at-rest encryption (BLOB column + migration, or boundary encryption) is **deferred to the credentials audit batch 3**; do not re-add String→String converters.
- `service/.../util/SecureStorage.kt` (the keystore-backed AES-GCM utility intended for batch-3 encryption) uses `android.security.keystore.KeyGenParameterSpec`/`KeyProperties`, which require API 23 while minSdk is 21. **Contract (2026-08-07)**: `init` is annotated `@RequiresApi(Build.VERSION_CODES.M)` with an `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return` as its first statement, and the caller (`MainApplication.onCreate`) is wrapped in `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)`. When batch 3 wires real encryption, keep these guards (removing them reintroduces 6 NewApi lint errors and a NoClassDefFoundError crash on API 21-22); note `encrypt`/`decrypt` are safe below API 23 (Cipher/GCMParameterSpec only) but throw `check(initialized)` when init was skipped.

## Related

- [GitHub Actions auto-repair](./gh-action-auto-repair.md) — CI ops/gate (no local builds, gh-only ops)
- Task artifacts: `.trellis/tasks/08-06-supply-chain-fixes-batch1/` (prd.md, fix-*.md, check-*.md, summary.md)
