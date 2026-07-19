#!/usr/bin/env bash
# Materialize release signing.properties exclusively from GitHub Actions secrets.
# Required env: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
# Optional env: KEYSTORE_FILE (default $RUNNER_TEMP/cmfa-release.keystore)
set -euo pipefail

: "${KEYSTORE_BASE64:?KEYSTORE_BASE64 secret is required for release signing}"
: "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD secret is required for release signing}"
: "${KEY_ALIAS:?KEY_ALIAS secret is required for release signing}"
: "${KEY_PASSWORD:?KEY_PASSWORD secret is required for release signing}"

if [ -z "${RUNNER_TEMP:-}" ]; then
  echo "RUNNER_TEMP is required; this script is for GitHub Actions only." >&2
  exit 1
fi

KEYSTORE_FILE="${KEYSTORE_FILE:-$RUNNER_TEMP/cmfa-release.keystore}"
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE_FILE"
chmod 600 "$KEYSTORE_FILE"
test -s "$KEYSTORE_FILE"

# Refuse empty / whitespace-only credentials after expansion.
for value in "$KEYSTORE_PASSWORD" "$KEY_ALIAS" "$KEY_PASSWORD"; do
  if [ -z "${value//[[:space:]]/}" ]; then
    echo "Release signing secret resolved to an empty value." >&2
    exit 1
  fi
done

# Verify the keystore can be opened with the provided password/alias before Gradle runs.
keytool -list \
  -keystore "$KEYSTORE_FILE" \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias "$KEY_ALIAS" \
  >/dev/null

printf '%s\n' \
  "keystore.file=$KEYSTORE_FILE" \
  "keystore.password=$KEYSTORE_PASSWORD" \
  "key.alias=$KEY_ALIAS" \
  "key.password=$KEY_PASSWORD" \
  > signing.properties
chmod 600 signing.properties

echo "Release signing credentials prepared from GitHub secrets (alias=$KEY_ALIAS)."
