#!/usr/bin/env bash
# All design states (design stateModel.qaCombos) + representative poses, one qa_shot each, into $SH (default
# ../spatial-app-factory/apps/figure-model/build/shots). The figure must be standing (S3 reached) before running.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export SH="${SH:-$ROOT/../spatial-app-factory/apps/figure-model/build/shots}"
Q="bash $ROOT/tools/qa_shot.sh"
SPOT="place=-0.25,-1.9"
$Q - setup "$SPOT" "light=-45,45" "lang=zh" "order=shuffle" "pose=1" "duration=30" >/dev/null
$Q S3 S3 "pose=3"
$Q S4 S4
$Q S5 S5
$Q S6 S6
$Q S7 S7
$Q S8 S8
$Q S9 S9-turned
$Q S3 S9-snap "yaw=10" "stick=0.3" "stick=off"
$Q S10 S10-right
$Q S10 S10-top "light=0,80"
$Q S3 S3-light-left "light=-45,45"
$Q S11 S11
$Q S12 S12-set1
$Q - S12-set2 "next"
$Q figure - >/dev/null; rm -f "$SH/-.png"
$Q S13 S13
$Q full - >/dev/null
$Q S14 S14
$Q S3 S17-panel "lang=en"
$Q S14 S17-menu
$Q - - "lang=zh" >/dev/null
$Q S18 S18
for n in 9 13 2 5 10 11 15 18 20; do $Q S3 pose$n "pose=$n"; done
$Q S16 S16
$Q - - "blocked=0" >/dev/null
$Q S15 S15
$Q - - "nofloor=0" >/dev/null
$Q S2 S2
sleep 10
$Q S1 S1
