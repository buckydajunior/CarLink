#!/usr/bin/env bash
# CarLink dev loop: build, test, install and screenshot on an explicit device or emulator.
#
#   dev.sh build            debug APK; optional auth from explicit DIPLAY_AUTH_ASSETS_DIR
#   dev.sh test             all unit-test suites (app, carlink-core, CarPlay engine) + resource collision check
#   dev.sh install          install the debug APK on the emulator and launch CarLink
#   dev.sh shot <name>      screenshot the emulator to /tmp/carlink-shots/<name>.png
#   dev.sh size <WxH> [dpi] resize the emulator screen (e.g. 1080x2400 420 for a phone; "reset")
#   dev.sh logs             CarLink/engine log lines since the last install
#   dev.sh build-release    the shrunk (R8) release APK; signed with the debug key unless ANDROID_KEYSTORE_* is set
#   dev.sh install-release  install the release APK and launch it
#
# Uses the caller's JAVA_HOME and Android SDK. Set CARLINK_DEVICE to an attached device serial;
# without it, device commands select a single running emulator. No physical device is assumed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
SERIAL="${CARLINK_DEVICE:-${CARLINK_EMULATOR:-}}"
if [ "${CARLINK_TARGET:-}" = tablet ]; then
  SERIAL="${CARLINK_TABLET:?Set CARLINK_TABLET to the intended device serial; no device address is assumed}"
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
# Keep the deprecated variable consistent when a shell already exports it.
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="${CARLINK_ADB:-$ANDROID_HOME/platform-tools/adb}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
property() {
  awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "$ROOT/gradle.properties"
}
RELEASE_PKG="$(property carlink.applicationId)"
[ -n "$RELEASE_PKG" ] || { echo "carlink.applicationId is missing from gradle.properties" >&2; exit 2; }
PKG="$RELEASE_PKG$(property carlink.debugApplicationIdSuffix)"
SHOTS="${CARLINK_SHOTS_DIR:-${TMPDIR:-/tmp}/carlink-shots}"
gradle_args=()
if [ -n "${CARLINK_GRADLE_ARGS:-}" ]; then read -r -a gradle_args <<< "$CARLINK_GRADLE_ARGS"; fi

adb_e() { "$ADB" -s "$SERIAL" "$@"; }

ensure_target() {
  [ -x "$ADB" ] || { echo "adb not found at $ADB; set ANDROID_HOME or CARLINK_ADB" >&2; exit 3; }
  if [ -z "$SERIAL" ]; then
    local emulators
    emulators="$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')"
    if [ "$(printf '%s\n' "$emulators" | awk 'NF { n++ } END { print n+0 }')" -ne 1 ]; then
      echo "Start one emulator in Android Studio, or set CARLINK_DEVICE to the intended adb serial." >&2
      exit 3
    fi
    SERIAL="$emulators"
  fi
  if ! adb_e get-state >/dev/null 2>&1; then
    echo "Device $SERIAL is not available; check adb devices and CARLINK_DEVICE." >&2
    exit 3
  fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  build)
    cd "$ROOT"
    ./gradlew --no-daemon -q :app:assembleDebug ${gradle_args[@]+"${gradle_args[@]}"} "$@"
    echo "built $APK"
    ;;
  test)
    cd "$ROOT"
    python3 -I scripts/resource_collisions.py --check
    ./gradlew --no-daemon -q --continue :app:testDebugUnitTest :carlink-core:testDebugUnitTest :carplay-shared:testDebugUnitTest :carplay-common:testDebugUnitTest ${gradle_args[@]+"${gradle_args[@]}"} "$@"
    echo "all tests passed"
    ;;
  build-release)
    # The release build as it ships (R8, no debuggable flag, arm ABIs only; add
    # -Pcarlink.emulatorAbi=true via CARLINK_GRADLE_ARGS for the emulator). Without a release
    # keystore in ANDROID_KEYSTORE_*, it is signed with the local debug key: for testing only.
    cd "$ROOT"
    sign=()
    if [ -z "${ANDROID_KEYSTORE_PATH:-}" ]; then
      sign=(ANDROID_KEYSTORE_PATH="$HOME/.android/debug.keystore" ANDROID_KEYSTORE_PASSWORD=android
            ANDROID_KEY_ALIAS=androiddebugkey ANDROID_KEY_PASSWORD=android)
    fi
    # Lint vital is skipped: this is a test build, and it doubles the build time.
    env ${sign[@]+"${sign[@]}"} ./gradlew --no-daemon -q :app:assembleRelease -x lintVitalAnalyzeRelease ${gradle_args[@]+"${gradle_args[@]}"} "$@"
    echo "built release $RELEASE_APK"
    ;;
  install-release)
    ensure_target
    if adb_e shell pm path "$PKG" 2>/dev/null | grep -q package:; then
      echo "note: the debug build ($PKG) is also installed; both can react to USB plugs." >&2
      echo "      For a clean release test, uninstall it first: adb -s $SERIAL uninstall $PKG" >&2
    fi
    adb_e install -r "$RELEASE_APK" | tail -1
    adb_e logcat -c
    adb_e shell am start -n "$RELEASE_PKG/com.carlink.app.ui.HomeActivity" >/dev/null
    echo "launched release on $SERIAL"
    ;;
  install)
    ensure_target
    adb_e install -r "$APK" | tail -1
    adb_e logcat -c
    adb_e shell am start -n "$PKG/com.carlink.app.ui.HomeActivity" >/dev/null
    echo "launched on $SERIAL"
    ;;
  shot)
    ensure_target
    mkdir -p "$SHOTS"
    sleep "${CARLINK_SHOT_DELAY:-1}"
    adb_e exec-out screencap -p > "$SHOTS/${1:-shot}.png"
    echo "$SHOTS/${1:-shot}.png"
    ;;
  size)
    ensure_target
    if [ "${1:-}" = reset ]; then adb_e shell wm size reset; adb_e shell wm density reset
    else adb_e shell wm size "$1"; [ -n "${2:-}" ] && adb_e shell wm density "$2"; fi
    ;;
  logs)
    ensure_target
    adb_e logcat -d -v time | grep -iE "CarLink|xcertplay|DiPlay|openheadunit|AndroidRuntime|FATAL" | tail -"${1:-80}"
    ;;
  *) sed -n '2,14p' "$0"; exit 1 ;;
esac
