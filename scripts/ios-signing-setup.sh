#!/bin/bash
set -euo pipefail

echo "Setting up iOS signing..."

: "${IOS_CERT_P12_B64:?IOS_CERT_P12_B64 is required}"
: "${IOS_CERT_PASSWORD:?IOS_CERT_PASSWORD is required}"
: "${IOS_PROFILE_B64:?IOS_PROFILE_B64 is required}"

TMP_DIR="${TMPDIR:-/tmp}"
JOB_ID="${CI_JOB_ID:-local}"

KEYCHAIN_PATH="$HOME/Library/Keychains/ci-${JOB_ID}.keychain-db"
KEYCHAIN_PASSWORD="$(openssl rand -hex 16)"

CERT_PATH="$TMP_DIR/ci-cert-${JOB_ID}.p12"
PROFILE_PATH="$TMP_DIR/ci-${JOB_ID}.mobileprovision"
PROFILE_PLIST="$TMP_DIR/ci-profile-${JOB_ID}.plist"
ENV_FILE="$TMP_DIR/ios-signing-${JOB_ID}.env"
WWDR_G3_PATH="$TMP_DIR/AppleWWDRCAG3.cer"
DIST_CERT_PATH="$TMP_DIR/ios-distribution-cert-${JOB_ID}.pem"

# ---------------------------------------------------------------------------
# P12
# ---------------------------------------------------------------------------

echo "$IOS_CERT_P12_B64" | base64 --decode > "$CERT_PATH"

echo "=== P12 check ==="

openssl pkcs12 \
  -in "$CERT_PATH" \
  -passin pass:"$IOS_CERT_PASSWORD" \
  -info \
  -noout \
  2>&1

# ---------------------------------------------------------------------------
# Apple WWDR G3 intermediate certificate
# ---------------------------------------------------------------------------

echo "=== Apple WWDR G3 ==="

curl -fsSL \
  "https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer" \
  -o "$WWDR_G3_PATH"

openssl x509 \
  -inform DER \
  -in "$WWDR_G3_PATH" \
  -noout \
  -subject \
  -issuer \
  -dates

# The runner should normally already contain WWDR G3.
# Import may fail for the customer user if System.keychain is not writable.
security import "$WWDR_G3_PATH" \
  -k /Library/Keychains/System.keychain \
  2>/dev/null || true

# ---------------------------------------------------------------------------
# Temporary keychain
# ---------------------------------------------------------------------------

security delete-keychain "$KEYCHAIN_PATH" 2>/dev/null || true

security create-keychain \
  -p "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN_PATH"

security set-keychain-settings \
  -lut 21600 \
  "$KEYCHAIN_PATH"

security unlock-keychain \
  -p "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN_PATH"

security import "$CERT_PATH" \
  -P "$IOS_CERT_PASSWORD" \
  -f pkcs12 \
  -k "$KEYCHAIN_PATH" \
  -T /usr/bin/codesign \
  -T /usr/bin/security

security unlock-keychain \
  -p "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN_PATH"

security set-key-partition-list \
  -S "apple:" \
  -s \
  -k "$KEYCHAIN_PASSWORD" \
  "$KEYCHAIN_PATH" > /dev/null

# ---------------------------------------------------------------------------
# Configure keychain search list
# ---------------------------------------------------------------------------

security list-keychains \
  -d user \
  -s \
  "$KEYCHAIN_PATH" \
  "/Library/Keychains/System.keychain"

security default-keychain \
  -d user \
  -s "$KEYCHAIN_PATH"

echo "=== User keychain search list ==="
security list-keychains -d user

echo "=== Default keychain ==="
security default-keychain -d user

echo "=== Signing identities ==="
security find-identity -v -p codesigning

# ---------------------------------------------------------------------------
# Certificate information
# ---------------------------------------------------------------------------

openssl pkcs12 \
  -in "$CERT_PATH" \
  -passin pass:"$IOS_CERT_PASSWORD" \
  -clcerts \
  -nokeys \
  -out "$DIST_CERT_PATH"

echo "=== Certificate trust ==="
security verify-cert -c "$DIST_CERT_PATH"

SIGNING_CERT_SHA1=$(
  openssl x509 \
    -in "$DIST_CERT_PATH" \
    -noout \
    -fingerprint \
    -sha1 |
  cut -d= -f2 |
  tr -d ':'
)

SIGNING_IDENTITY=$(
  openssl x509 \
    -in "$DIST_CERT_PATH" \
    -noout \
    -subject \
    -nameopt RFC2253 |
  sed -E 's/^subject=//' |
  tr ',' '\n' |
  sed -n 's/^CN=//p' |
  head -n 1
)

if [ -z "$SIGNING_IDENTITY" ]; then
  echo "Could not determine signing identity from certificate"
  exit 1
fi

echo "Signing certificate SHA-1: $SIGNING_CERT_SHA1"
echo "Signing identity: $SIGNING_IDENTITY"

# ---------------------------------------------------------------------------
# Provisioning profile
# ---------------------------------------------------------------------------

echo "$IOS_PROFILE_B64" | base64 --decode > "$PROFILE_PATH"

security cms \
  -D \
  -i "$PROFILE_PATH" \
  > "$PROFILE_PLIST"

PROFILE_UUID=$(plutil -extract UUID raw "$PROFILE_PLIST")
PROFILE_NAME=$(plutil -extract Name raw "$PROFILE_PLIST")
TEAM_ID=$(plutil -extract TeamIdentifier.0 raw "$PROFILE_PLIST")

APPLICATION_ID=$(
  plutil \
    -extract Entitlements.application-identifier \
    raw \
    "$PROFILE_PLIST"
)

BUNDLE_ID="${APPLICATION_ID#${TEAM_ID}.}"

echo "=== Provisioning profile ==="
echo "Name: $PROFILE_NAME"
echo "UUID: $PROFILE_UUID"
echo "Team ID: $TEAM_ID"
echo "Bundle ID: $BUNDLE_ID"

# ---------------------------------------------------------------------------
# Verify provisioning profile matches signing certificate
# ---------------------------------------------------------------------------

echo "=== Provisioning profile certificates ==="

python3 - "$PROFILE_PLIST" "$SIGNING_CERT_SHA1" <<'PY'
import hashlib
import plistlib
import sys

profile_path = sys.argv[1]
expected = sys.argv[2]

with open(profile_path, "rb") as f:
    profile = plistlib.load(f)

certificates = [
    hashlib.sha1(cert).hexdigest().upper()
    for cert in profile.get("DeveloperCertificates", [])
]

for cert in certificates:
    print(" ", cert)

if expected not in certificates:
    print()
    print(
        f"ERROR: Signing certificate {expected} "
        "is NOT included in provisioning profile"
    )
    sys.exit(1)

print()
print("OK: Signing certificate matches provisioning profile")
PY

# ---------------------------------------------------------------------------
# Install provisioning profile
# ---------------------------------------------------------------------------

mkdir -p \
  "$HOME/Library/MobileDevice/Provisioning Profiles" \
  "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"

cp "$PROFILE_PATH" \
  "$HOME/Library/MobileDevice/Provisioning Profiles/$PROFILE_UUID.mobileprovision"

cp "$PROFILE_PATH" \
  "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/$PROFILE_UUID.mobileprovision"

# ---------------------------------------------------------------------------
# Export signing environment for build-ios-ipa.sh
# ---------------------------------------------------------------------------

cat > "$ENV_FILE" <<EOF
export IOS_CI_KEYCHAIN_PATH="$KEYCHAIN_PATH"
export IOS_CI_KEYCHAIN_PASSWORD="$KEYCHAIN_PASSWORD"
export IOS_CI_PROFILE_UUID="$PROFILE_UUID"
export IOS_CI_PROFILE_NAME="$PROFILE_NAME"
export IOS_CI_PROFILE_PLIST="$PROFILE_PLIST"
export IOS_CI_TEAM_ID="$TEAM_ID"
export IOS_CI_BUNDLE_ID="$BUNDLE_ID"
export IOS_CI_SIGNING_IDENTITY="$SIGNING_IDENTITY"
EOF

# ---------------------------------------------------------------------------
# Cleanup temporary certificate files
# ---------------------------------------------------------------------------

rm -f "$CERT_PATH"
rm -f "$WWDR_G3_PATH"
rm -f "$DIST_CERT_PATH"

echo "iOS signing setup complete."
