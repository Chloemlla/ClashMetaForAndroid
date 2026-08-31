#!/usr/bin/env bash
# Resolve the latest lumen-crash main auto-release version and write lumen-crash.resolved.version.
# Prefer GitHub Packages-compatible releases tagged lumen-crash-v*.
# Falls back to an existing lumen-crash.resolved.version when GitHub API is unavailable.
set -euo pipefail

OWNER_REPO="${LUMEN_CRASH_OWNER_REPO:-Chloemlla/Project-Lumen}"
OUT_FILE="${LUMEN_CRASH_VERSION_FILE:-lumen-crash.resolved.version}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Every success path ends here: knowing the version is only half the job, the artifact still has to
# be reachable. Staging it from the public release keeps the build off the read-packages PAT.
stage_and_exit() {
  LUMEN_CRASH_OWNER_REPO="$OWNER_REPO" \
  LUMEN_CRASH_VERSION_FILE="$OUT_FILE" \
    bash "$HERE/sync-lumen-crash-local-maven.sh"
  exit 0
}

# If a pinned version file exists, use it directly and skip API resolution.
# This allows the repo to pin a specific lumen-crash version when the latest
# release is incompatible with the project's Kotlin version.
PIN_FILE="${LUMEN_CRASH_PIN_FILE:-lumen-crash.pinned.version}"
if [[ -s "$PIN_FILE" ]]; then
  pinned_version="$(tr -d '\r\n' < "$PIN_FILE")"
  printf '%s\n' "$pinned_version" > "$OUT_FILE"
  echo "Pinned lumen-crash version: $pinned_version -> $OUT_FILE (from $PIN_FILE)"
  stage_and_exit
fi

# If the resolved version file already exists, keep it (don't overwrite).
# This preserves the committed version when no pin file is present.
if [[ -s "$OUT_FILE" ]]; then
  existing="$(tr -d '\r\n' < "$OUT_FILE")"
  echo "Keeping existing lumen-crash version: $existing"
  stage_and_exit
fi

API_URL="https://api.github.com/repos/${OWNER_REPO}/releases?per_page=100"

auth_args=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  auth_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
elif [[ -n "${GH_TOKEN:-}" ]]; then
  auth_args+=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

tmp_json="$(mktemp)"
trap 'rm -f "$tmp_json"' EXIT

if ! curl -fsSL \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: clashmeta-lumen-crash-resolver" \
  "${auth_args[@]}" \
  "$API_URL" \
  -o "$tmp_json"; then
  if [[ -s "$OUT_FILE" ]]; then
    echo "GitHub API unavailable; keeping existing $OUT_FILE ($(tr -d '\r\n' < "$OUT_FILE"))" >&2
    stage_and_exit
  fi
  echo "Failed to fetch releases and no existing $OUT_FILE" >&2
  exit 1
fi

if ! version="$(
  python3 - "$tmp_json" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, "r", encoding="utf-8") as fh:
    releases = json.load(fh)
candidates = []
for rel in releases:
    if rel.get("draft"):
        continue
    tag = rel.get("tag_name") or ""
    if not tag.startswith("lumen-crash-v"):
        continue
    published = rel.get("published_at") or rel.get("created_at") or ""
    candidates.append((published, tag[len("lumen-crash-v"):]))
if not candidates:
    raise SystemExit("No lumen-crash-v* release found")
candidates.sort(reverse=True)
print(candidates[0][1])
PY
)"; then
  if [[ -s "$OUT_FILE" ]]; then
    echo "Failed to parse releases; keeping existing $OUT_FILE ($(tr -d '\r\n' < "$OUT_FILE"))" >&2
    stage_and_exit
  fi
  exit 1
fi

if [[ -z "${version}" ]]; then
  echo "Failed to resolve lumen-crash version" >&2
  exit 1
fi

printf '%s\n' "$version" > "$OUT_FILE"
echo "Resolved lumen-crash version: $version -> $OUT_FILE"
stage_and_exit
