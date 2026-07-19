#!/usr/bin/env bash
# Discover release APKs, require secret-keystore signature, stage for publish.
# Usage: stage-signed-apks.sh <stage-dir> [checksum-name]
set -euo pipefail

STAGE_DIR="${1:?stage directory required}"
CHECKSUM_NAME="${2:-SHA256SUMS}"
KEYSTORE_FILE="${KEYSTORE_FILE:-${RUNNER_TEMP:-}/cmfa-release.keystore}"
SEARCH_ROOT="${APK_SEARCH_ROOT:-app/build}"

if [ ! -f signing.properties ]; then
  echo "signing.properties missing; refuse to stage unsigned/debug APKs." >&2
  exit 1
fi

if [ ! -f "$KEYSTORE_FILE" ]; then
  echo "Release keystore not found at $KEYSTORE_FILE" >&2
  exit 1
fi

keystore_password=$(grep -E '^keystore\.password=' signing.properties | head -n1 | cut -d= -f2-)
key_alias=$(grep -E '^key\.alias=' signing.properties | head -n1 | cut -d= -f2-)
if [ -z "$keystore_password" ] || [ -z "$key_alias" ]; then
  echo "signing.properties is incomplete." >&2
  exit 1
fi

# Expected cert fingerprint from the GitHub-secret keystore.
expected_fp=$(
  keytool -list -v \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$keystore_password" \
    -alias "$key_alias" \
    2>/dev/null \
  | awk -F': ' 'tolower($1) ~ /sha256/ { gsub(/:/,"",$2); print toupper($2); exit }'
)
if [ -z "$expected_fp" ]; then
  echo "Unable to read SHA-256 fingerprint for alias $key_alias from secret keystore." >&2
  exit 1
fi

mapfile -t apks < <(find "$SEARCH_ROOT" -type f -name '*.apk' ! -path '*/ci-apks/*' | sort)
if [ "${#apks[@]}" -eq 0 ]; then
  echo "No APKs found under $SEARCH_ROOT" >&2
  find app/build -type d 2>/dev/null | head -n 120 || true
  exit 1
fi

printf 'Discovered %s APK(s):\n' "${#apks[@]}"
printf '  %s\n' "${apks[@]}"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
APKSIGNER=""
if [ -n "$SDK_ROOT" ]; then
  APKSIGNER=$(find "$SDK_ROOT/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n 1 || true)
fi
if [ -z "$APKSIGNER" ]; then
  echo "apksigner not found under ANDROID_HOME; cannot verify release signatures." >&2
  exit 1
fi

mkdir -p "$STAGE_DIR"
: > "$STAGE_DIR/$CHECKSUM_NAME"

for apk in "${apks[@]}"; do
  name=$(basename "$apk")
  case "$name" in
    *-unsigned.apk|*debug*|*Debug*)
      echo "Refusing debug/unsigned APK for release publish: $apk" >&2
      exit 1
      ;;
  esac

  "$APKSIGNER" verify --verbose "$apk" >/dev/null

  # Normalize apksigner cert digests to a bare uppercase hex SHA-256.
  actual_fp=$(
    "$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
    | awk '
        /Signer #1 certificate SHA-256 digest:/ {
          gsub(/:/,"",$NF)
          print toupper($NF)
          exit
        }
      '
  )
  if [ -z "$actual_fp" ]; then
    # Older apksigner wording variants.
    actual_fp=$(
      "$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
      | awk '
          tolower($0) ~ /sha-256 digest/ {
            gsub(/:/,"",$NF)
            print toupper($NF)
            exit
          }
        '
    )
  fi
  if [ -z "$actual_fp" ]; then
    echo "Could not read signer SHA-256 from $apk" >&2
    "$APKSIGNER" verify --print-certs "$apk" || true
    exit 1
  fi

  if [ "$actual_fp" != "$expected_fp" ]; then
    echo "APK is not signed with the GitHub-secret release keystore:" >&2
    echo "  apk:      $apk" >&2
    echo "  expected: $expected_fp" >&2
    echo "  actual:   $actual_fp" >&2
    exit 1
  fi

  cp -f "$apk" "$STAGE_DIR/$name"
  (cd "$STAGE_DIR" && sha256sum "$name") >> "$STAGE_DIR/$CHECKSUM_NAME"
done

printf 'Staged %s secret-signed release APK(s) into %s\n' "${#apks[@]}" "$STAGE_DIR"
ls -la "$STAGE_DIR"
