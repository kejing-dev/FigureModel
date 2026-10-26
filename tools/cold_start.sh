#!/usr/bin/env bash
# Clean start + ANR watch: tools/cold_start.sh [SECONDS]
# Resets the emulator camera (the Stage origin is the camera pose at launch), force-stops the app, starts it and
# for SECONDS (default 60) checks every 5 s for an "isn't responding" dialog or an am_anr event; prints the state log.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
AVD=${AVD:-Pico_Emulator_6_1_fresh}
PKG=io.embodit.figuremodel
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
UIA="$(cygpath -w "$ROOT/tools/harness/uia_cam.ps1")"
secs=${1:-60}
MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$UIA" reset -Avd $AVD | tr -d '\r' | head -1
MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$UIA" set camera_rotation_button on -Avd $AVD | tr -d '\r' | head -1
"$ADB" -s $DEV shell am force-stop $PKG; sleep 3
"$ADB" -s $DEV logcat -c
t0=$(date +%s)
"$ADB" -s $DEV shell am start -n $PKG/.platform.LaunchActivity >/dev/null 2>&1
anr=0
while [ $(( $(date +%s) - t0 )) -lt "$secs" ]; do
  sleep 5
  n=$("$ADB" -s $DEV shell "dumpsys window windows | grep -c 'Application Not Responding'" | tr -d '\r')
  a=$("$ADB" -s $DEV logcat -d -b events 2>/dev/null | grep -c "am_anr")
  if [ "${n:-0}" != "0" ] || [ "${a:-0}" != "0" ]; then anr=1; echo "ANR at +$(( $(date +%s) - t0 )) s (dialogs=$n events=$a)"; fi
done
echo "anr=$anr after $secs s"
"$ADB" -s $DEV shell pidof $PKG >/dev/null && echo "process alive" || echo "PROCESS DIED"
"$ADB" -s $DEV logcat -d -s FigureModel:V | grep -E "FM-STATE|SCENE|PLANES|PLACE|FATAL|failed" | cut -c19-200 | head -30
"$ADB" -s $DEV logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head -5
