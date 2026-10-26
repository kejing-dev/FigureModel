#!/usr/bin/env bash
# Panel stress test (factory rule A4): open / close the More menu and switch durations and the language 30 times,
# then report the process is alive; capture a frame with qa_shot.sh afterwards to check the glass panels still draw. tools/stress_panels.sh [ROUNDS]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.figuremodel
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
rounds=${1:-30}
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
adb -s $DEV logcat -c
cmd "state=S3"; sleep 2
for i in $(seq 1 "$rounds"); do
  cmd "menu"; sleep 0.35; cmd "menu"; sleep 0.25
  cmd "duration=60"; sleep 0.25; cmd "lang=en"; sleep 0.25; cmd "lang=zh"; sleep 0.25; cmd "duration=30"; sleep 0.25
done
echo "rounds=$rounds done"
adb -s $DEV shell pidof $PKG >/dev/null && echo "process alive" || echo "PROCESS DIED"
adb -s $DEV logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|Process: $PKG" | head -3
adb -s $DEV logcat -d -s FigureModel:V 2>/dev/null | grep -c "FM-STATE S14"
