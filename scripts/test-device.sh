#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the installed Android SDK}"
cd "$(dirname "$0")/.."
adb="$ANDROID_HOME/platform-tools/adb"
serial="${ANDROID_SERIAL:-emulator-5554}"
mkdir -p artifacts
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
revoke() { "$adb" -s "$serial" shell pm revoke cn.renewboard.debug android.permission.POST_NOTIFICATIONS; }
original_font_scale=$("$adb" -s "$serial" shell settings get system font_scale | tr -d '\r')
restore_font() {
    if [[ "$original_font_scale" == null ]]; then
        "$adb" -s "$serial" shell settings delete system font_scale
    else
        "$adb" -s "$serial" shell settings put system font_scale "$original_font_scale"
    fi
}
trap 'restore_font; revoke' EXIT
revoke
"$adb" -s "$serial" shell am instrument -w -r -e notClass cn.renewboard.GrantedReminderWorkerDeviceTest cn.renewboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/device-tests.txt
# Android's instrument command can return zero even when JUnit fails.
grep -Eq '^OK \([0-9]+ tests?\)' artifacts/device-tests.txt
"$adb" -s "$serial" shell settings put system font_scale 1.6
"$adb" -s "$serial" shell am force-stop cn.renewboard.debug
"$adb" -s "$serial" shell am instrument -w -r -e class cn.renewboard.CalendarDateDeviceTest#largeFontCalendarDaysStaySingleLineAndSelectable cn.renewboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/large-font-test.txt
grep -Eq '^OK \([0-9]+ tests?\)' artifacts/large-font-test.txt
restore_font
"$adb" -s "$serial" shell am instrument -w -r -e class cn.renewboard.GrantedReminderWorkerDeviceTest cn.renewboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/notification-granted-test.txt
grep -Eq '^OK \([0-9]+ tests?\)' artifacts/notification-granted-test.txt
