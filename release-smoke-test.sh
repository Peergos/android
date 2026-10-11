#!/usr/bin/env bash
# Starts a minified release build on the connected device and fails if it dies.
#
# The instrumented tests run the debug build, which R8 never touches, so they cannot
# see a release that only breaks once minified - like #38, where R8 moved netty's
# VarHandle calls into a class loaded at startup and Android 11 and 12 refused it.
#
#   ./release-smoke-test.sh app/build/outputs/apk/release/app-release-unsigned.apk
set -euo pipefail

apk=$1
package=peergos.android
seconds=${SMOKE_SECONDS:-30}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
apksigner=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)apksigner

# a release apk comes out unsigned, and any key will do to install it
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
keytool -genkeypair -keystore "$work/key" -storepass android -keypass android -alias smoke \
    -keyalg RSA -dname CN=smoke -validity 1 -noprompt > /dev/null 2>&1
"$apksigner" sign --ks "$work/key" --ks-pass pass:android --out "$work/signed.apk" "$apk"

# a build signed with another key cannot be installed over
adb uninstall "$package" > /dev/null 2>&1 || true
adb install "$work/signed.apk"
adb logcat -c
adb logcat -b crash -c
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 > /dev/null
sleep "$seconds"

crash=$(adb logcat -d -b crash)
pid=$(adb shell pidof "$package" || true)
# the instrumented tests install a debug build, which this one's key would refuse
adb uninstall "$package" > /dev/null 2>&1 || true
if [ -n "$crash" ] || [ -z "$pid" ]; then
    echo "::error::$package did not survive ${seconds}s after launch"
    echo "$crash"
    adb logcat -d -v threadtime | grep -E "AndroidRuntime|failed to verify|VerifyError" | tail -40 || true
    exit 1
fi
echo "$package is still running ${seconds}s after launch (pid $pid)"
