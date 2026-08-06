# Supply-chain hardening (signing / submodule / deps / Maven mirror)

Executable contracts for the 2026-08-06 batch-1 supply-chain fixes (audit STOP-A/B/F/G/H, task `08-06-supply-chain-fixes-batch1`).
Keep actual build/test execution in GitHub Actions; local builds/deps are forbidden per project rule.

## 1. Scope / Trigger

- Trigger: 网安审计发现 PR 事件用生产密钥签名、签名材料物理在磁盘、子模块浮动 Alpha、Go 依赖 CVE、第三方 Maven 镜像无完整性校验。

## 2. Signatures — signing gate (`.github/workflows/build-debug.yaml`)

- Any step that reads `secrets.KEYSTORE_*` or stages/uploads a **signed** APK MUST be gated:
  ```yaml
  if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.repository_owner == 'Chloemlla'
  ```
- PR runs (`pull_request`) build **unsigned** output only (debug / unit / lint / negative test `Release signing must fail without credentials`).
- `Remove signing credentials` cleanup keeps `if: always()`.
- Affected steps: `Prepare release signing`, `Build signed Alpha release APK`, `Stage secret-signed Alpha APKs`, `Prepare Alpha migration APK aliases`, `Upload APKs`.
- Verify: `grep -n "KEYSTORE_" .github/workflows/build-debug.yaml` — every hit must be inside a gated step.

## 3. Keystore hygiene contract

- **Never** place keystore material in the repo working directory, even if gitignored.
- `setup-android-signing.ps1` writes base64 to `$env:TEMP\cmfa-signing-<random>\keystore_base64.txt` (randomized dir), offers cleanup, and never writes `keystore_base64.txt` into the repo root.
- Keystore backup (outside repo): `F:\Repositories\GitHub\clashmeta-keystore-backup\`.
- Rotation of any keystore that touched disk is a **maintainer action**, not an automated fix.

## 4. Policy checks (`.github/scripts/verify-repository-policy.py`)

| Condition | Result |
| --- | --- |
| `keystore_base64.txt` or any `*.jks` / `*.p12` / `*.keystore` file anywhere under ROOT, except dirs `.git`, `.gradle`, `build`, `tmp`, `.trellis` | `require(False, ...)` → build fails |
| `.gitmodules` contains a `branch =` line | `require(False, ...)` → build fails |

Exclusion must prune **subtrees**: `exclude_dirs.intersection(found.relative_to(ROOT).parts)`.
Do NOT use `if found.is_dir() and found.name in exclude_dirs: continue` — `Path.rglob("*")` still yields children of the excluded dir (traverses `.git/`, defeats exclusion).

## 5. Submodule & Go dependency contracts

- `.gitmodules`: no `branch =` line — submodules pinned to recorded commits.
- `update-dependencies.yaml` is the ONLY workflow that may run `git submodule update --remote`; the update PR body must carry the new submodule commit hash for review (step `Record submodule commit for review`, `body-path: /tmp/pr-body.md`), and must never auto-merge.
- Go security bump runs in CI (never locally): for both `core/src/main/golang` and `core/src/foss/golang`:
  ```bash
  go get -u golang.org/x/net golang.org/x/crypto golang.org/x/sys && go mod tidy
  ```
  Path math: from workspace root, `cd core/src/main/golang` then `cd ../../foss/golang` → `core/src/foss/golang` (correct).
- go.sum is regenerated **in CI only**. Do NOT hand-edit `go.mod` version lines — local go.sum regeneration is impossible under the no-local-deps rule; a hand-edit desyncs the build.
- This bump resolves: CVE-2025-22872 (x/net), CVE-2024-45338, CVE-2023-45288, CVE-2024-45337/45336 (x/crypto).

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

## Related

- [GitHub Actions auto-repair](./gh-action-auto-repair.md) — CI ops/gate (no local builds, gh-only ops)
- Task artifacts: `.trellis/tasks/08-06-supply-chain-fixes-batch1/` (prd.md, fix-*.md, check-*.md, summary.md)
