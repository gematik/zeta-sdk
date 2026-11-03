#!/bin/bash
set -euo pipefail

echo "Building iOS IPA..."

TMP_DIR="${TMPDIR:-/tmp}"
JOB_ID="${CI_JOB_ID:-local}"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

IOS_PROJECT="${IOS_PROJECT:-iosApp/iosApp.xcodeproj}"
IOS_SCHEME="${IOS_SCHEME:-iosApp}"
IOS_EXPORT_METHOD="${IOS_EXPORT_METHOD:-enterprise}"

ENV_FILE="$TMP_DIR/ios-signing-${JOB_ID}.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "Signing environment not found: $ENV_FILE"
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

# ---------------------------------------------------------------------------
# Signing configuration from ios-signing-setup.sh
# ---------------------------------------------------------------------------

: "${IOS_CI_KEYCHAIN_PATH:?IOS_CI_KEYCHAIN_PATH is required}"
: "${IOS_CI_PROFILE_UUID:?IOS_CI_PROFILE_UUID is required}"
: "${IOS_CI_TEAM_ID:?IOS_CI_TEAM_ID is required}"
: "${IOS_CI_BUNDLE_ID:?IOS_CI_BUNDLE_ID is required}"
: "${IOS_CI_SIGNING_IDENTITY:?IOS_CI_SIGNING_IDENTITY is required}"

TEAM_ID="$IOS_CI_TEAM_ID"
BUNDLE_ID="$IOS_CI_BUNDLE_ID"
SIGNING_IDENTITY="$IOS_CI_SIGNING_IDENTITY"

BUILD_DIR="build/ios"
ARCHIVE_PATH="$BUILD_DIR/app.xcarchive"
EXPORT_PATH="$BUILD_DIR/export"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# ---------------------------------------------------------------------------
# Check signing environment
# ---------------------------------------------------------------------------

echo "=== Keychains visible to build ==="
security list-keychains -d user

echo "=== Signing identities visible to build ==="
security find-identity -v -p codesigning

echo "=== Signing configuration ==="
echo "Keychain: $IOS_CI_KEYCHAIN_PATH"
echo "Profile UUID: $IOS_CI_PROFILE_UUID"
echo "Bundle ID: $BUNDLE_ID"
echo "Team ID: $TEAM_ID"
echo "Signing identity: $SIGNING_IDENTITY"

# ---------------------------------------------------------------------------
# Archive
# ---------------------------------------------------------------------------

echo "Archiving $IOS_SCHEME..."

xcodebuild archive \
  -project "$IOS_PROJECT" \
  -scheme "$IOS_SCHEME" \
  -configuration Release \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -archivePath "$ARCHIVE_PATH" \
  PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID" \
  CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  CODE_SIGN_IDENTITY="$SIGNING_IDENTITY" \
  PROVISIONING_PROFILE_SPECIFIER="$IOS_CI_PROFILE_UUID"

# ---------------------------------------------------------------------------
# Export options
# ---------------------------------------------------------------------------

EXPORT_OPTIONS="$BUILD_DIR/ExportOptions.plist"

cat > "$EXPORT_OPTIONS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>$IOS_EXPORT_METHOD</string>

    <key>teamID</key>
    <string>$TEAM_ID</string>

    <key>signingStyle</key>
    <string>manual</string>

    <key>provisioningProfiles</key>
    <dict>
        <key>$BUNDLE_ID</key>
        <string>$IOS_CI_PROFILE_UUID</string>
    </dict>

    <key>stripSwiftSymbols</key>
    <true/>
</dict>
</plist>
EOF

# ---------------------------------------------------------------------------
# Export IPA
# ---------------------------------------------------------------------------

echo "Exporting IPA..."

xcodebuild \
  -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportPath "$EXPORT_PATH" \
  -exportOptionsPlist "$EXPORT_OPTIONS"

IPA_PATH=$(find "$EXPORT_PATH" -name "*.ipa" -type f | head -n 1)

if [ -z "$IPA_PATH" ]; then
  echo "No IPA generated"
  exit 1
fi

cp "$IPA_PATH" app-browserstack.ipa

echo "IPA created:"
ls -lh app-browserstack.ipa
