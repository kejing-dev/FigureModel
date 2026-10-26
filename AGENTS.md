# Figure Model (速写模特)

A life-size wooden art mannequin stands on your real floor and changes pose every 30 s / 1 min / 5 min while
you draw it with real paper and pencil. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.figuremodel`, v0.1.0.

Design package (binding): `../spatial-app-factory/apps/figure-model/design-package.json` (archetype D, container
STAGE_MIXED, window model single_panel_with_popup, 18 states S1 - S18, 20 interactions, 20 poses as joint-angle
tables, copy, rules). Mockup (passed gate B): `../spatial-app-factory/apps/figure-model/sample.html`; its
`buildFigure` is the kinematics ported to `Mannequin.kt`. Skeleton: `../robot-cell` (from `../fire-plan` and
`../spatial-ruler`; that project's `AGENTS.md` holds the SDK findings this app relies on).

Built by the factory's build station on 2026-10-25 (debug); self-tested on emulator-5560 (AVD
Pico_Emulator_6_1_fresh) on 2026-10-26: three clean starts without "isn't responding", all 18 states and 9 poses
captured, snaps / dwell / light checked, 30-round panel stress passed. Not run on a headset. The domain is covered
by JVM unit tests (`app/src/test`, all 20 poses on the floor).

## Structure

```
app/src/main/java/io/embodit/figuremodel/
  Main.kt                      DefaultStage { FigureTheme { FmScreen() } }
  platform/                    SpatialApplication, LaunchActivity, FmGraph (wiring), FmLog (tag FigureModel)
    sense/PlaneTracker         plane detection -> PlaneSurface (Stage metres)                [Spatial Ruler]
    tracking/HeadTracker       HMD pose, block reason                                         [Spatial Ruler]
    input/ControllerInput      controller ray, trigger, thumbstick X, A / X presses           [Fire Plan + A/X]
  domain/model                 Vec3 / Quat4, PlaneSurface, FigureModel (settings, enums), Poses (20 angle tables,
                               5 still-life sets), Mannequin (forward kinematics, grounding, knee levelling)
  domain/usecase               Rules (LightRule az/el + presets + orb clamps, PoseSequence shuffle rounds,
                               PlaceRule auto placement on the floor), Flat, HitSurface, FacePanel, ViewFrame
  data/SettingsStore           SharedPreferences: duration, light, orb, order, content, size, sound, language
  ui/theme                     FmColors (the only colour literals), FigureTheme (PicoTheme, 16 roles), Noto fonts
  ui/fm                        FmViewModel (the state machine + clock), FmUiState / FmEvent, FmScreen
  ui/fm/components             FmStage (SpatialView + panel pool), ControlPanel, MoreMenu, BlockedDialog, Common
  ui/fm/scene                  FmScene (one ECS frame System: pose easing, smoothing, stick, A/X, dwell, light,
                               rings, ghost, panel follow + dodge, 100 ms clock tick), FmViews (mannequin, pedestal,
                               still life, stand ring, timer ring, orb, next disc), PanelRig, PanelSpec,
                               PlaneColliders (table catchers + floor slab, S18 only; backstop), SceneKit
  ui/fm/debug/FmProbe          seams for the debug receiver (no-ops in release)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
tools/make_icon.py             icon: adaptive foreground vector, legacy PNG and the 512 export, from one shape list
```

One direction of data: panel controls, scene callbacks (taps, drags, the stick, A / X, dwell, floor, head ten times
a second, block reason, the 100 ms tick) and debug hooks -> `FmViewModel.onEvent` -> `FmUiState` ->
`FmScene.render` and the panels. Per-frame work lives in `FmScene.onFrame` only; the panel countdown changes once a
second, the floor ring arc reads `endsAtMs` itself.

## The mannequin

- `Mannequin.solve(angles)`: the mockup's FK (pelvis 0.95 m before grounding, shoulders 0.38 / hips -0.05 from the
  waist ball, upper arm 0.30, forearm 0.27, hand 0.17, thigh 0.42, shin 0.41; right side = -X, front = +Z). 30 parts:
  ellipsoids (head, chest, pelvis) and joint balls are a scaled unit sphere, limbs are frusta (radius 1 -> taper,
  length 1), hands / feet exact-size rounded boxes. A pose change only writes transforms; 0.6 s ease-in-out blend of
  the angle arrays.
- Grounding: lowest of the soles, every foot-box corner (instep for kneeling, outer edge for cross-legged), the knees
  and the pelvis goes to the floor, plus the pose's lift (run 0.05, jump 0.25). The pivot (stick turns) is the
  vertical between the ankles.
- `Mannequin.settle` (once per pose): joint limits; support feet without a table ankle value stay flat
  (ankle = hip flex - knee flex); both-feet poses level their soles to 2 cm by one-degree steps on a knee, at most 15
  degrees per knee (log line `POSES settled ... knee_fix={5=24, 7=12, 15=9, 19=14}`).
- Unit test `FigureDomainTest` prints each pose's soles / head / pelvis; head top of pose 1 = 1.765 m, seat 0.44 m.

## Room content and rules

- Stand ring (36 dashes Ø 0.9 m, small Ø 0.3 m on a TABLE, arrow 0.25 m): 25 % when the figure is on it, 100 % while
  dragging / off the ring / 2 s after placing, green 0.3 s on a snap. Timer ring r 0.38 m (72 pieces, used up
  clockwise from the figure's front; alert orange in the last 5 s; breathes while paused; hidden for ∞).
- Auto placement: first FLOOR (largest by hull area) + a settled head pose -> ring 1.8 m ahead (1.0 m desk tier),
  clamped 0.3 m inside the floor polygon, arrow towards the viewer; the figure fades in from 30 % in 0.4 s.
- Drag (system drag on the figure / pedestal): hand travel -> floor metres along the head's right / forward; drop
  within 0.15 m snaps the position, and the heading too within 20 degrees ("已站回圈上", OpDragEnd sound).
- Stick X: dead zone 0.15, 60 degrees/s at full tilt, linear stop over 0.2 s; release on the ring within 15 degrees
  of the arrow snaps the heading (arrow flashes green). The stick never starts while dragging and vice versa.
- Light: `DirectionalLightComponent` (SDK light, design's first choice; warm white 2600 lux) along orb -> centre,
  a faint emissive (4 %) on the wood; optional fill light via `lux=main,fill` (off by default).
  Wood is PBR (roughness 0.82). Azimuth 0 = the figure's front at placement; presets computed from where the viewer
  stands; orb drawn on the 1.4 m (desk 0.6 m) orbit, pulled in to <= 2.2 m height and >= 0.6 m from the head.
  Dragging the orb moves it across the view; the direction follows.
- Next disc: waist height 1.05 m, 0.55 m to the viewer's right of the figure (desk 0.35 m at top + 0.15 m), out to
  0.70 m while a limb is within 0.10 m (back after 0.5 s), on the left when the right spot is within 0.2 m of the
  floor edge; head ray within 0.09 m for 1.5 s -> next (rim fills 0.3 - 1.5 s, cool-down 0.8 s); a tap also works.
- A (right) / X (left) controller button: next pose (design I6; `ControllerAction.aButtonPressed / xButtonPressed`).
- Still life: pedestal 0.45 x 0.75 x 0.45 + five plaster sets (meshes built the first time a set shows); desk figure
  = mannequin at 0.2 on the pedestal; on a TABLE neither pedestal nor big ring.
- Seated pose: stool under the pelvis (its height follows the pelvis, 0.44 m), fades 0.3 s.

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels (`PanelSpec`), 1 dp = 0.8 mm at scale 1, all at 0.75: `controlPanel` 400 x 268 (name +
chip + More / countdown 110 + four duration segments 250 / previous, main, next / two-line hint with the stick icon),
`moreMenu` 400 x 480 (light presets 72, then orb, order, content, size, sound, language, move the ring, quit; 8 dp
right of the control panel, tops aligned), `blockedDialog` 360 x 200 (0.6 m ahead, 5 degrees down).
Control panel follow: yaw only. Height = eye height (median of the first 3 s, then slow) + 0.07 m, 30 degrees left
of the anchor heading, 0.65 m from the head; slides (0.4 s) when the head turns more than 45 degrees or walks more
than 0.5 m for 0.5 s; never while looking down past -20 degrees, while the menu is open or while dragging.
PanelDodge: when the figure's bounds overlap the panel's angular box it moves up to 15 degrees further left, then up
to 12 degrees up; back after 1 s clear. Copy: `res/values/strings.xml` (en) and `res/values-zh`, read through `rs()`
so the language switch needs no restart; pose names come from `Poses` (zh / en).

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.figuremodel.DEBUG -p io.embodit.figuremodel --es cmd 'state=S4'"
adb -s <serial> shell "am broadcast -a io.embodit.figuremodel.DEBUG -p io.embodit.figuremodel --es cmd 'pose=9'"
```

| command | effect |
|---|---|
| `state=S1` … `state=S18` | straight to that designed state (overlays cleared; S3 - S11 and S18 need the figure placed, i.e. after S3 was reached once). S5 = 4 s frozen, S6 = next pose held half way, S8 = figure 0.45 m off the ring with the drag hint, S9 = turned 75 degrees with the turn hint, S10 = right preset + light hint, S11 = dwell 0.9 s. S12 / S13 / S17 stack (undo: `state=figure`, `state=full`, `state=zh`) |
| `pose=1..20`, `time=<s>`, `freeze=1\|0` | jump to a pose; freeze the countdown at s seconds; stop / restart the clock |
| `light=<az>,<el>`, `size=full\|desk`, `mode=figure\|still`, `lang=zh\|en`, `order=shuffle\|seq`, `duration=30\|60\|300\|inf` | settings |
| `stick=<-1..1>\|off`, `dwell=<0..1.5>\|off`, `yaw=<deg>`, `offset=<dx>,<dz>` | held stick / dwell; turn or move the figure off the ring (then `stick=off` or a drop tests the snaps) |
| `nofloor=1\|0`, `blocked=1\|0`, `place=<x>,<z>[,table]`, `pitch=<deg>\|off`, `lux=<main>[,<fill>]` | S15, S16, ring to Stage coordinates, simulated head pitch for the panel, light intensities |
| `start`, `next`, `prev`, `menu`, `relocate`, `exit`, `ui <control>` | panel actions; `ui` presses a control by name (main, prev, next, more, dur_30/60/300/inf, light_left/right/front/back/top, orb_show/hide, order_shuffle/seq, content_figure/still, size_full/desk, sound_on/off, lang_zh/en, menu_relocate, menu_exit, dialog_retry, dialog_exit) |
| `dump`, `hmd`, `panels`, `planes`, `figure`, `park` | queries (`DUMP {json}` also in files/dump.json) |

Log tag `FigureModel`: `FM-STATE <Sx> pose=<n> t=<s> [+S12 +S13 +S17]` on every state change (overlay first: S18,
S14, S8, S9, S10, S11, S6, S5, then the main state), plus `PLANES`, `PLACE`, `PLACED`, `POSE`, `TIMER`, `SNAP`,
`STICK`, `DWELL`, `BUTTON`, `DRAG`, `TAP`, `LIGHT`, `RELOCATE`, `FOLLOW`, `PANEL`, `SOUND`, `BLOCKED`, `CMD`, `EVENT`.

Screenshot list (design `stateModel.qaCombos`): S1, S2 (`state=S2` holds 8 s), S3 (panorama + panel close-up +
`pitch=-25`), S4, S5, S6, S7, S8, S9 (side) and S9 snap (`yaw=10`, `stick=0.6`, `stick=off`), S10 right and top
(`light=0,80`), S11, S12 set 1 and 2 (`state=S12`, `next`), S13, S14, S15, S16, S17 (S3 panel + S14 menu), S18,
S3 + pose 9 (stool), S3 + pose 13 (off the floor). Scripts: `tools/relaunch.sh [install]`, `tools/qa_shot.sh S4`,
`tools/stress_panels.sh 30` (set `DEV`).

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src`
must be empty.

## Deviations from the design package (recorded in the factory's state.json)

- Light: the SDK directional light is used (design first choice); the vertex-colour fallback is not built. It runs at
  10000 lux: at 2600 the passthrough light estimate drowned it on the emulator (front / back light then differ
  clearly, side lights only a little). No fill light by default; a 4 % emissive on the wood. Ground shadows come
  from `GroundShadowComponent` (straight down), not from the lamp.
- Limbs share one frustum taper (0.74; design 0.70 - 0.78); hands and feet are scaled unit cubes (no rounded edges).
- PanelDodge moves the panel up first when 12 degrees are enough, otherwise left (then up).
- Feet: support feet stand flat (ankle follows hip and knee when the table has no ankle value); levelling may use
  up to 15 degrees on each knee (lunge 24, push 12, throw 9, fall back 14); hip spread is applied after hip flex;
  pose 12 hip rotation 40 -> 75 degrees, otherwise the cross-legged figure floats 0.47 m up on its feet.
- Stool height follows the pelvis (0.44 m instead of 0.40 m) so the seated figure sits on it.
- S15: when a floor turns up while S15 shows (not forced by `nofloor=1`), the app places the figure by itself.
- The orb drag moves the orb across the view (both channels); no ray / orbit intersection.
- Hint icon: the stick icon leads the hint line in every state (design: "行首内联 ic_stick").
- Noto Sans SC bundled whole (17 MB).

## Emulator self-test (2026-10-26, emulator-5560)

- Shots: `../spatial-app-factory/apps/figure-model/build/shots/` (S1 - S18 with S9 / S10 / S12 / S17 variants, S11 by
  real gaze `S11-gaze.png`, poses 2 5 9 10 11 13 15 18 20), `build/first-screen.png`, `build/launcher.png` (system
  "Back to Home" dialog with the app icon), `build/stress-after-30.png`. Recipe: `tools/cold_start.sh` (camera reset,
  clean start, 60 s ANR watch), `tools/shots_all.sh` (every state; ring placed at x -0.25, z -1.9 so the figure
  does not push the panel out of view), `tools/stress_panels.sh 30`. Scripts default to emulator-5560.
- Emulator room: floor y = 0.40, head 1.65; the reset camera faces a corridor wall 1.8 m ahead, so the disc moves to
  the figure's left (wall rule) and the ring touches the wall. The first floor arrives in about 5 s; the scene is
  ready about 36 s after launch because the 11 meshes are built one per 2.5 s while the emulator idles at 1 - 2 fps.
- Found and fixed: `createBox` renders double size on the emulator (unit cube from a MeshModel); all meshes at once
  is a start-up ANR (paced); lamp too weak against the light estimate (10000 lux); a pooled panel kept a half-drawn
  frame (black under the menu; re-composed 1.5 / 4 / 8 s after it opens or changes); the panel dodged out of the
  emulator's view (up first now).
- Verified by broadcast: drop 11 cm off with 12 degrees -> snaps (`SNAP drop d=0.112 yaw_snap=true`), 30 cm -> no
  snap; stick release 7 degrees off -> snaps, 38 degrees -> stays; light presets / `light=` move the lamp. Real gaze:
  camera turned onto the disc -> S11 -> next pose after 1.5 s (`source=dwell`), again after the 0.8 s cool-down while
  still looking. HOME -> Back to Home -> Confirm: `SENSING stopped (background)`, no crash.
- Not verified: thumbstick and A / X on a real controller (eye mode on the emulator), pinch drag feel, sounds, the
  headset light look, panel dodge with a real eye height (the emulator's eye is only 1.25 m above the floor).
