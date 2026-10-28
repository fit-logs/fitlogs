#!/usr/bin/env bash
# Only the isolated preview on an emulator may receive synthetic fixtures.
set -euo pipefail
serial="${1:?Usage: capture_ui_audit.sh emulator-SERIAL OUTPUT_DIRECTORY}"
out="${2:?Supply a screenshot output directory}"
[[ "$serial" == emulator-* ]] || { echo 'An emulator serial is required.' >&2; exit 2; }
[[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Refusing a physical device.' >&2; exit 2; }
[[ "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" == 37 ]] || { echo 'Screenshot baseline requires API 37.' >&2; exit 2; }
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
cd "$root"
./gradlew -PphotoVerification=true :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Match the reviewed light-theme fixture. Restore system preferences on exit.
font="$(adb -s "$serial" shell settings get system font_scale | tr -d '\r')"
night="$(adb -s "$serial" shell settings get secure ui_night_mode | tr -d '\r')"
restore() {
  adb -s "$serial" shell settings put system font_scale "$font" >/dev/null
  case "$night" in 1) adb -s "$serial" shell cmd uimode night no >/dev/null;; 2) adb -s "$serial" shell cmd uimode night yes >/dev/null;; *) adb -s "$serial" shell cmd uimode night auto >/dev/null;; esac
}
trap restore EXIT
adb -s "$serial" shell settings put system font_scale 1.0
adb -s "$serial" shell cmd uimode night no
adb -s "$serial" shell am instrument -w -r -e auditPhase after -e class com.example.fitnotes.UiAuditCaptureTest com.example.fitnotes.preview.photos.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/journeys.txt"
# am instrument can return shell success for a failed JUnit run.
rg -q '^OK \(5 tests\)' "$out/journeys.txt"
for scene in train-empty plans-empty plans-list plan-detail history-empty progress-empty train-ready history progress train-active workout logger-empty logger-keyboard set-editor logger; do
  adb -s "$serial" exec-out run-as com.example.fitnotes.preview.photos cat "files/audit-after-$scene.png" > "$out/$scene.png"
done
