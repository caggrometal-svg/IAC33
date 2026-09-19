#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="app/build/iac33-connected-e2e"
mkdir -p "$REPORT_DIR"
exec > >(tee "$REPORT_DIR/console.log") 2>&1

ADB_DEVICE="${ANDROID_SERIAL:-}"

log() {
  echo "[$(date -u +%H:%M:%S)] $*"
}

resolve_adb_device() {
  if [[ -n "$ADB_DEVICE" ]]; then
    return 0
  fi

  ADB_DEVICE="$(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ {print $1; exit}')"
  [[ -n "$ADB_DEVICE" ]]
}

adb_cmd() {
  adb -s "$ADB_DEVICE" "$@"
}

diagnostics() {
  adb devices || true
  if [[ -n "$ADB_DEVICE" ]]; then
    adb_cmd get-state || true
    adb_cmd shell getprop sys.boot_completed || true
    adb_cmd shell getprop ro.build.version.sdk || true
    adb_cmd logcat -d -t 500 > "$REPORT_DIR/logcat.txt" 2>/dev/null || true
    adb_cmd shell dumpsys activity activities > "$REPORT_DIR/activity.txt" 2>/dev/null || true
    adb_cmd shell dumpsys package cl.iac33.app > "$REPORT_DIR/package.txt" 2>/dev/null || true
    adb_cmd shell dumpsys package cl.iac33.app.test > "$REPORT_DIR/test-package.txt" 2>/dev/null || true
  fi
}

wait_for_android() {
  local deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    resolve_adb_device || true
    if [[ -n "$ADB_DEVICE" ]]; then
      local state
      state="$(adb_cmd get-state 2>/dev/null || true)"
      if [[ "$state" == "device" ]]; then
        if adb_cmd shell getprop sys.boot_completed 2>/dev/null | grep -q '^1$'; then
          return 0
        fi
      elif [[ "$state" == "offline" || "$state" == "unauthorized" ]]; then
        adb reconnect offline >/dev/null 2>&1 || true
        adb reconnect device >/dev/null 2>&1 || true
      fi
    fi
    sleep 2
  done
  echo "Android emulator did not become ready." >&2
  diagnostics
  return 1
}

configure_verifier() {
  adb_cmd shell settings put global verifier_verify_adb_installs 0 || true
  adb_cmd shell settings put global package_verifier_enable 0 || true
  adb_cmd shell settings put global package_verifier_user_consent 1 || true
  adb_cmd shell settings put global verifier_timeout 120000 || true
  adb_cmd shell settings put global streaming_verifier_timeout 120000 || true
  adb_cmd shell settings put global app_integrity_verification_timeout 120000 || true
}

run_instrumented() {
  local class_name="$1"
  log "=== Running $class_name ==="
  timeout 90s adb_cmd shell am instrument -w -r     -e class "$class_name"     cl.iac33.app.test/androidx.test.runner.AndroidJUnitRunner
}

trap 'status=$?; if [[ $status -ne 0 ]]; then echo "=== Connected E2E diagnostics ==="; diagnostics; fi; exit $status' EXIT

wait_for_android
configure_verifier

log "SDK:"
adb_cmd shell getprop ro.build.version.sdk
log "Device:"
adb devices

TARGET_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

test -s "$TARGET_APK"
test -s "$TEST_APK"

log "Installing target APK..."
timeout 90s adb_cmd install -r -t "$TARGET_APK"

log "Installing instrumentation APK..."
timeout 90s adb_cmd install -r -t "$TEST_APK"

# Do not clear app data here: DeviceIdentity intentionally couples the Android
# Keystore key with local identity metadata. Clearing only application data can
# invalidate that pairing and create a false E2E failure.
run_instrumented "cl.iac33.app.MainActivitySmokeTest"
run_instrumented "cl.iac33.app.control.DeviceControlE2ETest"

echo "Android connected E2E: PASS"
