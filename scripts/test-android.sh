#!/usr/bin/env bash
# test-android.sh — run the instrumented tests on the shared emulator.
#
# History
#   2026-08-08  A. Sigdel  Created.
#
# A device found by name and created when
# absent, so a machine that has never run this needs no setup beyond the SDK. It
# boots one headless when nothing is up, because these tests draw nothing.
#
# The core is built first. Gradle only packages the library, so an APK assembled
# without it fails with UnsatisfiedLinkError — the failure these tests exist to
# catch, and indistinguishable from a real one.
#
# Usage
#   scripts/test-android.sh
#
# Environment
#   ANDROID_HOME        The SDK. Default below.
#   WATTROUTER_AVD      Device to use, created if missing.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
readonly AVD="${WATTROUTER_AVD:-wattrouter-tests}"
readonly IMAGE="system-images;android-35;google_apis;arm64-v8a"
readonly ADB="$SDK/platform-tools/adb"
readonly WAIT_LIMIT=300

if [ ! -x "$ADB" ]; then
    cat >&2 <<EOF
No Android SDK at $SDK. Install the tools and an image:

  brew install --cask temurin android-commandlinetools
  brew install gradle
  sdkmanager --sdk_root="$SDK" --licenses
  sdkmanager --sdk_root="$SDK" platform-tools emulator "$IMAGE"

The licences step prints Google's terms and waits for you to accept them.
EOF
    exit 1
fi

# The SDK's own avdmanager, not one on PATH. A cmdline-tools installed elsewhere
# — Homebrew's, for instance — infers its SDK root from its own location and
# reports every system image as missing, which reads as a bad package name.
readonly AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"

if ! ANDROID_HOME="$SDK" "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $AVD"; then
    printf 'creating emulator %s\n' "$AVD" >&2
    echo no | ANDROID_HOME="$SDK" "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" --force >/dev/null
fi

# Booted, not merely listed. `adb devices` shows an emulator that is shutting
# down and one that is still starting, and both take a test run with them — a
# device in the list is not a device that can install an APK.
ready() {
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

started=""
if ! ready; then
    printf 'booting %s\n' "$AVD" >&2
    ANDROID_HOME="$SDK" nohup "$SDK/emulator/emulator" -avd "$AVD" \
        -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        >/tmp/wattrouter-emulator.log 2>&1 &
    started=yes

    waited=0
    until ready; do
        if [ "$waited" -ge "$WAIT_LIMIT" ]; then
            printf 'emulator did not boot in %ss; see /tmp/wattrouter-emulator.log\n' "$waited" >&2
            exit 1
        fi
        sleep 10
        waited=$((waited + 10))
    done
fi

# Only an emulator this script started is one it may stop. A device somebody
# else is using is not this script's to shut down, which is the mistake
# learned once with a booted emulator.
cleanup() {
    if [ -n "$started" ]; then
        "$ADB" emu kill >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# Take the app off first. Gradle cannot replace an APK signed with a different
# key, and the one that gets installed by hand here is the release build —
# `just android-release` produces something signed with the person's own key,
# and proving it loads the native library means installing it. After that the
# suite fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, which names a signature
# mismatch rather than the APK somebody installed twenty minutes earlier.
#
# `pm uninstall --user 0` rather than `adb uninstall`: the latter answers
# DELETE_FAILED_INTERNAL_ERROR here and the former does not, which is not
# something anybody guesses at the point of needing it.
#
# A no-op on the ordinary path, where the package is absent or debug-signed.
"$ADB" shell pm uninstall --user 0 com.getlora.wattrouter >/dev/null 2>&1 || true
"$ADB" shell pm uninstall --user 0 com.getlora.wattrouter.test >/dev/null 2>&1 || true

"$ROOT/scripts/build-android-core.sh" >/dev/null
cd "$ROOT/android"
ANDROID_HOME="$SDK" gradle connectedDebugAndroidTest --console=plain
