app_id := "de.gematik.zeta.client"
main_activity := app_id + "/de.gematik.zeta.client.MainActivity"

# list available recipes
default:
    @just --list

# build and install the debug APK on the connected device/emulator
install:
    ./gradlew :zeta-client:installDebug

# launch the demo app (installs first if needed)
run: install
    adb shell am start -n {{main_activity}}

# uninstall, reinstall, and launch fresh (forces FCM to issue a new token)
reinstall:
    adb uninstall {{app_id}} || true
    just run

# tail logcat filtered to the zeta client's own tags
logs:
    adb logcat

# tail logcat filtered to the FCM service (the push key itself is never logged; obtain it
# via a debugger breakpoint in ZetaFirebaseMessagingService.onNewToken)
fcm-logs:
    adb logcat | grep -i "ZetaFirebaseMsgService"

# list connected devices/emulators
devices:
    adb devices

# start the rooted google_apis emulator whose /system/etc/hosts maps
# zeta-kind.local to 10.0.2.2 (the host as seen from the emulator);
# -writable-system is required on every launch, otherwise the pristine
# read-only system image (without the hosts entry) is used
emulator avd="zeta-local":
    ~/Library/Android/sdk/emulator/emulator -avd {{avd}} -writable-system

# trust the Let's Encrypt staging root CAs on an iOS simulator (default: the booted device);
# needed so the client can validate TLS against a Fachdienst serving a staging-issued cert
# without DISABLE_SERVER_VALIDATION - re-run after erasing/recreating the simulator
ios-trust-le-staging device="booted":
    xcrun simctl keychain {{device}} add-root-cert test-keystore/letsencrypt-staging-root-x1.pem
    xcrun simctl keychain {{device}} add-root-cert test-keystore/letsencrypt-staging-root-x2.pem

# print the merged manifest for the debug variant
manifest:
    ./gradlew :zeta-client:processDebugMainManifest
    cat zeta-client/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml

# confirm the FCM service is registered on the installed package
check-service:
    adb shell dumpsys package {{app_id}} | grep -A5 ZetaFirebaseMessagingService

# send the test push-gateway notification; pass the pushkey obtained via the debugger
# (breakpoint in ZetaFirebaseMessagingService.onNewToken)
message pushkey pushkey_ts=`date +%s`:
    hurl --variable pushkey={{pushkey}} --variable pushkey_ts={{pushkey_ts}} ../test/TestNotify.hurl
