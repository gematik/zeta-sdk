#!/bin/bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

FACHDIENST_URL="${FACHDIENST_OIDC_URL:-${FACHDIENST_URL:-}}"

: "${FACHDIENST_URL:?FACHDIENST_OIDC_URL or FACHDIENST_URL is required}"
: "${OIDC_BASE_URI:?OIDC_BASE_URI is required}"
: "${OIDC_IDP_ISS:?OIDC_IDP_ISS is required}"
: "${OIDC_IDP_ALIAS:?OIDC_IDP_ALIAS is required}"
: "${OIDC_TEST_KVNR:?OIDC_TEST_KVNR is required}"

OIDC_BINDING_EMAIL="${OIDC_BINDING_EMAIL:-}"
POPP_TOKEN="${POPP_TOKEN:-}"
PUSH_GATEWAY_URL="${PUSH_GATEWAY_URL:-}"
PUSHER_APP_ID="${PUSHER_APP_ID:-}"

FIREBASE_APP_ID="${FIREBASE_APP_ID:-}"
FIREBASE_API_KEY="${FIREBASE_API_KEY:-}"
FIREBASE_PROJECT_ID="${FIREBASE_PROJECT_ID:-}"
FIREBASE_SENDER_ID="${FIREBASE_SENDER_ID:-}"

APP="de.gematik.zeta.android.testdriver"
AVD_NAME="zeta_android_14"

# The AVD baked into the Docker image is read-only at runtime.
IMAGE_AVD_HOME="/opt/android/avd"

# /tmp is backed by a writable Kubernetes emptyDir.
RUNTIME_AVD_HOME="/tmp/avd"


# -----------------------------------------------------------------------------
# Debug environment
# -----------------------------------------------------------------------------

echo "=================================================="
echo "[debug] Android emulator environment"
echo "=================================================="

echo "[debug] User:"
id || true
whoami || true

echo
echo "[debug] Important environment:"
echo "HOME=${HOME:-<not set>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<not set>}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<not set>}"
echo "ANDROID_SDK_HOME=${ANDROID_SDK_HOME:-<not set>}"
echo "ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-<not set>}"

echo
echo "[debug] Android binaries:"
command -v emulator || true
command -v adb || true
command -v avdmanager || true
command -v sdkmanager || true

echo
echo "[debug] Emulator version:"
emulator -version || true

echo
echo "[debug] AVD template:"
find "$IMAGE_AVD_HOME" -maxdepth 2 -print 2>/dev/null || true

echo
echo "[debug] Filesystems/mounts:"
mount | grep -E '/tmp|/opt|android' || true

echo
echo "[debug] Writable directories:"
for dir in /tmp /opt /opt/android "$IMAGE_AVD_HOME"; do
    if [ -e "$dir" ]; then
        if [ -w "$dir" ]; then
            echo "$dir -> WRITABLE"
        else
            echo "$dir -> READ-ONLY"
        fi
    else
        echo "$dir -> DOES NOT EXIST"
    fi
done

echo
echo "[debug] Disk:"
df -h || true

echo "=================================================="
echo "[debug] End Android environment"
echo "=================================================="


# -----------------------------------------------------------------------------
# Prepare writable AVD
# -----------------------------------------------------------------------------

echo "[entrypoint] preparing writable AVD"

if [ ! -d "$IMAGE_AVD_HOME/$AVD_NAME.avd" ]; then
    echo "[entrypoint] ERROR: AVD template not found:"
    echo "  $IMAGE_AVD_HOME/$AVD_NAME.avd"
    exit 1
fi

if [ ! -f "$IMAGE_AVD_HOME/$AVD_NAME.ini" ]; then
    echo "[entrypoint] ERROR: AVD ini not found:"
    echo "  $IMAGE_AVD_HOME/$AVD_NAME.ini"
    exit 1
fi

rm -rf "$RUNTIME_AVD_HOME"
mkdir -p "$RUNTIME_AVD_HOME"

echo "[entrypoint] copying AVD to writable storage"

cp -a \
    "$IMAGE_AVD_HOME/$AVD_NAME.avd" \
    "$RUNTIME_AVD_HOME/$AVD_NAME.avd"

cp \
    "$IMAGE_AVD_HOME/$AVD_NAME.ini" \
    "$RUNTIME_AVD_HOME/$AVD_NAME.ini"

# The .ini generated during the Docker build points to /opt/android/avd.
# Update it so that the emulator uses the writable copy.
sed -i \
    "s|^path=.*|path=$RUNTIME_AVD_HOME/$AVD_NAME.avd|" \
    "$RUNTIME_AVD_HOME/$AVD_NAME.ini"

export ANDROID_AVD_HOME="$RUNTIME_AVD_HOME"

echo
echo "[entrypoint] writable AVD prepared"
echo "[entrypoint] ANDROID_AVD_HOME=$ANDROID_AVD_HOME"

echo
echo "[debug] Runtime AVD:"
find "$ANDROID_AVD_HOME" -maxdepth 2 -print || true

echo
echo "[debug] Available AVDs:"
emulator -list-avds

if ! emulator -list-avds | grep -qx "$AVD_NAME"; then
    echo "[entrypoint] ERROR: $AVD_NAME not found"
    exit 1
fi


# -----------------------------------------------------------------------------
# Start emulator
# -----------------------------------------------------------------------------

echo
echo "[entrypoint] starting emulator: $AVD_NAME"

emulator "@$AVD_NAME" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-snapshot \
    -no-metrics \
    -gpu swiftshader_indirect &

EMU_PID=$!

echo "[entrypoint] emulator PID=$EMU_PID"


# -----------------------------------------------------------------------------
# Wait for Android
# -----------------------------------------------------------------------------

echo "[entrypoint] waiting for adb"

timeout 300 adb wait-for-device

echo "[entrypoint] waiting for Android boot"

timeout 300 bash -c '
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = "1" ]; do
        sleep 2
    done
'

echo "[entrypoint] emulator booted"


# -----------------------------------------------------------------------------
# Install Testdriver
# -----------------------------------------------------------------------------

echo "[entrypoint] installing testdriver"

adb install -r /opt/testdriver/app.apk

adb logcat -c


# -----------------------------------------------------------------------------
# Start Testdriver
# -----------------------------------------------------------------------------

echo "[entrypoint] starting testdriver"

quote_for_device() { printf "'%s'" "$1"; }

EXTRAS=()
add_extra() {
    local key="$1" value="$2"
    if [ -n "$value" ]; then
        EXTRAS+=(--es "$key" "$(quote_for_device "$value")")
    else
        echo "[entrypoint] extra $key is empty, not passed"
    fi
}

add_extra FACHDIENST_URL "$FACHDIENST_URL"
add_extra POPP_TOKEN "$POPP_TOKEN"
add_extra OIDC_BASE_URI "$OIDC_BASE_URI"
add_extra OIDC_IDP_ISS "$OIDC_IDP_ISS"
add_extra OIDC_IDP_ALIAS "$OIDC_IDP_ALIAS"
add_extra OIDC_TEST_KVNR "$OIDC_TEST_KVNR"
add_extra OIDC_BINDING_EMAIL "$OIDC_BINDING_EMAIL"
add_extra PUSH_GATEWAY_URL "$PUSH_GATEWAY_URL"
add_extra PUSHER_APP_ID "$PUSHER_APP_ID"
add_extra FIREBASE_APP_ID "$FIREBASE_APP_ID"
add_extra FIREBASE_API_KEY "$FIREBASE_API_KEY"
add_extra FIREBASE_PROJECT_ID "$FIREBASE_PROJECT_ID"
add_extra FIREBASE_SENDER_ID "$FIREBASE_SENDER_ID"

adb shell pm grant "$APP" android.permission.POST_NOTIFICATIONS || true

adb shell am start -n "$APP/.MainActivity" "${EXTRAS[@]}"


# -----------------------------------------------------------------------------
# Wait for Testdriver
# -----------------------------------------------------------------------------

echo "[entrypoint] waiting for app process"

timeout 60 bash -c "
    until adb shell pidof '$APP' >/dev/null 2>&1; do
        sleep 1
    done
" || {
    echo "[entrypoint] ERROR: app did not start"
    adb logcat -d -b crash || true
    exit 1
}

echo "[entrypoint] app running"


# -----------------------------------------------------------------------------
# Expose Testdriver outside the emulator
#
# Android emulator :8081
#       ↓ adb forward
# Pod localhost:8081
#       ↓ socat
# Pod 0.0.0.0:8080
# -----------------------------------------------------------------------------

echo "[entrypoint] configuring port forwarding"

adb forward tcp:8081 tcp:8081

socat \
    TCP-LISTEN:8080,fork,reuseaddr \
    TCP:127.0.0.1:8081 &

SOCAT_PID=$!

echo "[entrypoint] testdriver exposed on 0.0.0.0:8080"


# -----------------------------------------------------------------------------
# Android logs
# -----------------------------------------------------------------------------

adb logcat -v time \
    | grep --line-buffered -iE "zeta|AndroidRuntime" \
    | grep --line-buffered -v AppsFilter &


# -----------------------------------------------------------------------------
# Health monitoring
# -----------------------------------------------------------------------------

while true; do
    sleep 10

    if ! kill -0 "$EMU_PID" 2>/dev/null; then
        echo "[entrypoint] ERROR: emulator died"
        exit 1
    fi

    if ! kill -0 "$SOCAT_PID" 2>/dev/null; then
        echo "[entrypoint] ERROR: socat died"
        exit 1
    fi

    if ! adb shell pidof "$APP" >/dev/null 2>&1; then
        echo "[entrypoint] ERROR: app died"
        adb logcat -d -b crash || true
        exit 1
    fi
done
