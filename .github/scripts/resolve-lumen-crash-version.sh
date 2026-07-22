#!/usr/bin/env bash
# Resolve the latest lumen-crash main auto-release version and write lumen-crash.resolved.version.
# Prefer GitHub Packages-compatible releases tagged lumen-crash-v*.
set -euo pipefail

OWNER_REPO="${LUMEN_CRASH_OWNER_REPO:-Chloemlla/Project-Lumen}"
OUT_FILE="${LUMEN_CRASH_VERSION_FILE:-lumen-crash.resolved.version}"
API_URL="https://api.github.com/repos/${OWNER_REPO}/releases?per_page=100"

auth_header=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  auth_header=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
elif [[ -n "${GH_TOKEN:-}" ]]; then
  auth_header=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

version="$(
  curl -fsSL \
    -H "Accept: application/vnd.github+json" \
    -H "User-Agent: clashmeta-lumen-crash-resolver" \
    "${auth_header[@]}" \
    "$API_URL" \
  | python3 - <<'PY'
import json, sys
releases = json.load(sys.stdin)
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
)"

if [[ -z "${version}" ]]; then
  echo "Failed to resolve lumen-crash version" >&2
  exit 1
fi

printf '%s\n' "$version" > "$OUT_FILE"
echo "Resolved lumen-crash version: $version -> $OUT_FILE"