#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="app/build/iac33-connected-e2e"
mkdir -p "$REPORT_DIR"
exec > >(tee "$REPORT_DIR/console.log") 2>&1

diagnostics() {
  adb devices || true
  adb -s emulator-5554 get-state || true
  adb -s emulator-5554 shell getprop sys.boot_completed || true
  adb -s emulator-5554 shell getprop ro.build.version.sdk || true
  adb -s emulator-5554 logcat -d -t 500 > "$REPORT_DIR/logcat.txt" 2>/dev/null || true
  adb -s emulator-5554 shell dumpsys activity activities > "$REPORT_DIR/activity.txt" 2>/dev/null || true
  adb -s emulator-5554 shell dumpsys package cl.iac33.app > "$REPORT_DIR/package.txt" 2>/dev/null || true
}

wait_for_android() {
  local deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    local state
    state="$(adb -s emulator-5554 get-state 2>/dev/null || true)"
    if [[ "$state" == "device" ]]; then
      if adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | grep -q '^1$'; then
        return 0
      fi
    elif [[ "$state" == "offline" || "$state" == "unauthorized" ]]; then
      adb reconnect offline >/dev/null 2>&1 || true
      adb reconnect device >/dev/null 2>&1 || true
    fi
    sleep 2
  done
  echo "Android emulator did not become ready." >&2
  diagnostics
  return 1
}

configure_verifier() {
  adb -s emulator-5554 shell settings put global verifier_verify_adb_installs 0 || true
  adb -s emulator-5554 shell settings put global package_verifier_enable 0 || true
  adb -s emulator-5554 shell settings put global package_verifier_user_consent 1 || true
  adb -s emulator-5554 shell settings put global verifier_timeout 120000 || true
  adb -s emulator-5554 shell settings put global streaming_verifier_timeout 120000 || true
  adb -s emulator-5554 shell settings put global app_integrity_verification_timeout 120000 || true
}

run_instrumented() {
  local class_name="$1"
  echo "=== Running $class_name ==="
  timeout 150s adb -s emulator-5554 shell am instrument -w -r \
    -e class "$class_name" \
    cl.iac33.app.test/androidx.test.runner.AndroidJUnitRunner
}

trap 'status=$?; if [[ $status -ne 0 ]]; then echo "=== Connected E2E diagnostics ==="; diagnostics; fi; exit $status' EXIT

wait_for_android
configure_verifier

echo "SDK:"
adb -s emulator-5554 shell getprop ro.build.version.sdk
echo "Devices:"
adb devices

echo "Installing target APK..."
timeout 90s adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk

TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$TEST_APK" ]]; then
  echo "Missing instrumentation APK: $TEST_APK" >&2
  exit 1
fi

echo "Installing instrumentation APK..."
timeout 90s adb -s emulator-5554 install -r -t "$TEST_APK"

adb -s emulator-5554 shell pm clear cl.iac33.app >/dev/null 2>&1 || true

run_instrumented "cl.iac33.app.MainActivitySmokeTest"
run_instrumented "cl.iac33.app.control.DeviceControlE2ETest"

echo "Android connected E2E: PASS"
