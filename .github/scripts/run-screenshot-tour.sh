#!/usr/bin/env bash
# Runs the screenshot tour on an already-booted emulator and pulls the PNGs into ./screenshots.
# Used by .github/workflows/screenshots.yml; inputs come in as environment variables.
set -uo pipefail

DISPLAY_PROFILE="${DISPLAY_PROFILE:-phone}"
THEME="${THEME:-light}"
FONT_SCALE="${FONT_SCALE:-1.0}"
TESTS="${TESTS:-}"
[ -n "$TESTS" ] || TESTS="com.lifeadmin.autopilot.ScreenshotTour"
if ! [[ "$TESTS" =~ ^[A-Za-z0-9_.,#]+$ ]]; then echo "Invalid tests filter: $TESTS"; exit 1; fi
if ! [[ "$THEME" =~ ^(light|dark)$ ]]; then echo "Invalid theme: $THEME"; exit 1; fi
if ! [[ "$FONT_SCALE" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then echo "Invalid font scale: $FONT_SCALE"; exit 1; fi

case "$DISPLAY_PROFILE" in
  phone)  adb shell wm size 1080x2400; adb shell wm density 420 ;;  # ~411 x 914 dp, Pixel-like
  tablet) adb shell wm size 1600x2560; adb shell wm density 320 ;;  # 800 x 1280 dp
  small)  ;;                                                         # AVD native 320 x 640 dp
  *) echo "Unknown display profile: $DISPLAY_PROFILE"; exit 1 ;;
esac
if [ "$THEME" = dark ]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
adb shell settings put system font_scale "$FONT_SCALE"
adb shell settings put global hide_error_dialogs 1 || true

# Display/config changes restart the launcher; give it time so it doesn't show an ANR dialog
# over the app (the tour also taps "Wait" on any that still appear).
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null || true
adb shell wm size
adb shell wm density

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class "$TESTS" \
  com.lifeadmin.autopilot.debug.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt

mkdir -p screenshots
adb pull /sdcard/Android/data/com.lifeadmin.autopilot.debug/files/screenshots/. screenshots/ || true
cp instrument.txt screenshots/
ls screenshots

# `am instrument` exits 0 even when tests fail: fail the job so a broken tour is visible.
grep -q "^OK (" instrument.txt
