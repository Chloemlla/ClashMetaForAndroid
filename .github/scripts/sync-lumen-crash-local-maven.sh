#!/usr/bin/env bash
# Stage lumen-crash into ./local-maven from the public Project-Lumen release assets.
#
# Why this exists: the GitHubPackagesProjectLumen repository needs a token that can read another
# repository's packages, and GITHUB_TOKEN cannot — so the build depends on a hand-rotated PAT and
# dies with a bare 401 the day it expires. The same .aar/.pom/.module are attached to the public
# release, which needs no auth at all. local-maven is declared before GitHub Packages in
# build.gradle.kts, so once staged it wins and the PAT becomes a fallback instead of a hard
# dependency.
set -euo pipefail

OWNER_REPO="${LUMEN_CRASH_OWNER_REPO:-Chloemlla/Project-Lumen}"
VERSION_FILE="${LUMEN_CRASH_VERSION_FILE:-lumen-crash.resolved.version}"
LOCAL_MAVEN="${LUMEN_CRASH_LOCAL_MAVEN:-local-maven}"

if [[ ! -s "$VERSION_FILE" ]]; then
  echo "sync-lumen-crash: no $VERSION_FILE, nothing to stage" >&2
  exit 0
fi

version="$(tr -d '\r\n' < "$VERSION_FILE")"
target="$LOCAL_MAVEN/com/chloemlla/lumen/lumen-crash/$version"
base="https://github.com/$OWNER_REPO/releases/download/lumen-crash-v$version"
artifacts=(
  "lumen-crash-$version.aar"
  "lumen-crash-$version.pom"
  "lumen-crash-$version.module"
  # The module metadata declares a sources variant. CI never asks for it, but an IDE sync does,
  # and a local-maven mirror that is missing it fails only in the IDE — the worst place to find out.
  "lumen-crash-$version-sources.jar"
)

missing=0
for name in "${artifacts[@]}"; do
  [[ -s "$target/$name" ]] || missing=1
done
if [[ "$missing" -eq 0 ]]; then
  echo "sync-lumen-crash: $version already staged in $target"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Fail soft: GitHub Packages is still configured, so an unreachable release must not be the thing
# that fails the build. A wrong artifact is a different story — see the checksum check below.
if ! curl -fsSL "$base/checksums.txt" -o "$work/checksums.txt"; then
  echo "sync-lumen-crash: cannot fetch $base/checksums.txt; leaving resolution to GitHub Packages" >&2
  exit 0
fi

for name in "${artifacts[@]}"; do
  if ! curl -fsSL "$base/$name" -o "$work/$name"; then
    echo "sync-lumen-crash: cannot fetch $base/$name; leaving resolution to GitHub Packages" >&2
    exit 0
  fi
done

(
  cd "$work"
  # checksums.txt also lists sources.jar and the release notes; only check what was downloaded.
  : > expected.txt
  for name in "${artifacts[@]}"; do
    grep -F "  $name" checksums.txt >> expected.txt
  done
  sha256sum -c expected.txt
)

mkdir -p "$target"
for name in "${artifacts[@]}"; do
  mv -f "$work/$name" "$target/$name"
done

echo "sync-lumen-crash: staged $version into $target (checksums verified)"
