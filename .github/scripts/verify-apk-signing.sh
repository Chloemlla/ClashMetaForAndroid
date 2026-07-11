#!/usr/bin/env bash
set -euo pipefail

apk_directory=${1:?"usage: verify-apk-signing.sh <apk-directory>"}
expected_digest=${SIGNING_CERT_SHA256:?"SIGNING_CERT_SHA256 is required"}
expected_digest=${expected_digest//:/}
expected_digest=${expected_digest^^}

apksigner=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -print | sort -V | tail -n 1)
test -n "$apksigner"

mapfile -d '' apks < <(find "$apk_directory" -type f -name '*.apk' -print0 | sort -z)
test "${#apks[@]}" -gt 0

for apk in "${apks[@]}"; do
  verification=$("$apksigner" verify --verbose --print-certs "$apk")
  actual_digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$verification" | head -n 1)
  actual_digest=${actual_digest//:/}
  actual_digest=${actual_digest^^}
  test -n "$actual_digest"
  if [[ "$actual_digest" != "$expected_digest" ]]; then
    echo "Unexpected signing certificate for $apk" >&2
    exit 1
  fi
done

(
  cd "$apk_directory"
  sha256sum ./*.apk > SHA256SUMS
)
