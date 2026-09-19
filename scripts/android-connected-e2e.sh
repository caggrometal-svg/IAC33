#!/usr/bin/env bash
set -euo pipefail

LOG=/tmp/iac33-connected-e2e.log

wait_for_android() {
  timeout 90s adb wait-for-device
  for _ in {1..150}; do
    if adb shell getprop sys.boot_completed 2>/dev/null | grep -q '^1$'; then
      return 0
    fi
    sleep 2
  done
  echo "Android emulator did not reach sys.boot_completed=1" >&2
  return 1
}

configure_verifier() {
  adb shell settings put global verifier_verify_adb_installs 0 || true
  adb shell settings put global package_verifier_enable 0 || true
  adb shell settings put global package_verifier_user_consent 1 || true
  adb shell settings put global verifier_timeout 120000 || true
  adb shell settings put global streaming_verifier_timeout 120000 || true
  adb shell settings put global app_integrity_verification_timeout 120000 || true
}

run_connected_tests() {
  timeout 10m gradle connectedDebugAndroidTest --no-daemon --stacktrace --console=plain
}

wait_for_android
configure_verifier

adb shell getprop ro.build.version.sdk
adb devices
adb shell settings get global verifier_verify_adb_installs || true
adb shell settings get global package_verifier_enable || true

set +e
run_connected_tests > "$LOG" 2>&1
status=$?
set -e
cat "$LOG"

if [ "$status" -eq 0 ]; then
  exit 0
fi

if grep -Eq 'INSTALL_FAILED_VERIFICATION_FAILURE|adb protocol fault|Failed to install split APK|Integrity verification timed out|device offline|device unauthorized|more than one device/emulator' "$LOG"; then
  echo "Transient emulator/ADB/package-install failure detected; refreshing ADB and retrying once."
  adb kill-server || true
  adb start-server
  sleep 3
  wait_for_android
  configure_verifier
  run_connected_tests
  exit $?
fi

exit "$status"
