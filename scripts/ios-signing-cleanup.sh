#!/bin/bash
set -uo pipefail

echo "Cleaning up iOS signing..."

TMP_DIR="${TMPDIR:-/tmp}"
JOB_ID="${CI_JOB_ID:-local}"

ENV_FILE="$TMP_DIR/ios-signing-${JOB_ID}.env"

if [ -f "$ENV_FILE" ]; then
  source "$ENV_FILE"

  if [ -n "${IOS_CI_KEYCHAIN_PATH:-}" ]; then
    security delete-keychain "$IOS_CI_KEYCHAIN_PATH" || true
  fi

  if [ -n "${IOS_CI_PROFILE_UUID:-}" ]; then
    rm -f \
      "$HOME/Library/MobileDevice/Provisioning Profiles/$IOS_CI_PROFILE_UUID.mobileprovision" \
      "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/$IOS_CI_PROFILE_UUID.mobileprovision"
  fi

  rm -f "$ENV_FILE"
fi

rm -f \
  "$TMP_DIR/ci-cert-${JOB_ID}.p12" \
  "$TMP_DIR/ci-${JOB_ID}.mobileprovision" \
  "$TMP_DIR/ci-profile-${JOB_ID}.plist"

./gradlew --stop || true

echo "iOS signing cleanup complete."
