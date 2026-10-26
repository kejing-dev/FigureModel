package io.embodit.figuremodel.debug

import android.content.Context
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.LanguagePref
import io.embodit.figuremodel.domain.model.PoseDuration
import io.embodit.figuremodel.platform.FmLog
import io.embodit.figuremodel.ui.fm.FmEvent
import io.embodit.figuremodel.ui.fm.debug.FmProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `FigureModel`) answers every command.
 * Accepted forms: `--es cmd 'state=S3'`, `--es cmd 'state S3'`, `--es cmd state --es v S3`
 * (values with commas: `light=90,45`, `place=0.5,-2,table`).
 *
 * - `state=S1..S18`          straight to a designed state (overlays cleared first; S12 / S13 / S17 stack)
 * - `pose=1..20`             jump to a pose (with the 0.6 s transition)
 * - `time=<s>`               freeze the countdown at s seconds (S4 / S5 captures)
 * - `freeze=1|0`             stop / restart the clock; a transition holds half way (S6)
 * - `light=<az>,<el>`        light azimuth (0 = the figure's front at placement) and elevation 10..80
 * - `size=full|desk`, `mode=figure|still`, `lang=zh|en`, `order=shuffle|seq`, `duration=30|60|300|inf`
 * - `stick=<-1..1>|off`      a held thumbstick value (when stick.sh cannot drive it)
 * - `dwell=<0..1.5>|off`     a held dwell time on the next disc, seconds
 * - `yaw=<deg>`              turn the figure to the ring arrow + deg (then `stick=0.5`, `stick=off` tests the snap)
 * - `offset=<dx>,<dz>`       move the figure off the ring (metres); `drop` then ends a drag there (snap rule)
 * - `nofloor=1|0`, `blocked=1|0`
 * - `place=<x>,<z>[,table]`  the ring straight to Stage coordinates on the floor (or a table)
 * - `pitch=<deg>|off`        the panel behaves as if the head were pitched (panel must not follow below -20)
 * - `lux=<main>[,<fill>]`    directional light intensity (and an optional fill light)
 * - `start`, `next`, `prev`, `menu`, `relocate`, `exit`
 * - `ui <control>`           presses a panel control by name (main, prev, next, more, dur_30, dur_60, dur_300, dur_inf,
 *                            light_left|right|front|back|top, orb_show|hide, order_shuffle|seq, content_figure|still,
 *                            size_full|desk, sound_on|off, lang_zh|en, menu_relocate, menu_exit, dialog_retry, dialog_exit)
 * - `dump`                   the model as one JSON line (`DUMP {...}`), also files/dump.json
 * - `hmd`, `panels`, `planes`, `figure`, `park`
 */
internal object DebugCommands {

    fun run(context: Context, extras: Map<String, String>) {
        val raw = extras["cmd"]?.trim() ?: return
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return
        var name = tokens.removeAt(0).lowercase(Locale.US)
        val args = ArrayList<String>()
        if (name.contains('=')) {
            val v = name.substringAfter('=')
            name = name.substringBefore('=')
            args += v.split(',').filter { it.isNotEmpty() }
        }
        for (t in tokens) args += t.split(',').filter { it.isNotEmpty() }
        extras["v"]?.let { args += it.split(',') }
        val host = FmProbe.host
        if (host == null) {
            FmLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, args)
        } catch (t: Throwable) {
            FmLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: FmProbe.Host, name: String, args: List<String>) {
        val scene = host.scene()
        val first = args.getOrNull(0)?.lowercase(Locale.US) ?: ""
        fun num(i: Int): Float? = args.getOrNull(i)?.toFloatOrNull()
        fun off(): Boolean = first == "off" || first == "none" || first == "null"
        when (name) {
            "state", "goto" -> host.dispatch(FmEvent.DebugState(args.getOrNull(0) ?: return FmLog.i("CMD state needs S1..S18")))
            "pose" -> host.dispatch(FmEvent.DebugPose(num(0)?.toInt() ?: return FmLog.i("CMD pose needs 1..20")))
            "time" -> host.dispatch(FmEvent.DebugTime(num(0) ?: return FmLog.i("CMD time needs seconds")))
            "freeze" -> host.dispatch(FmEvent.DebugFreeze(first != "0" && first != "off"))
            "light" -> {
                val az = num(0) ?: return FmLog.i("CMD light needs az,el")
                val el = num(1) ?: 45f
                host.dispatch(FmEvent.LightSet(az, el))
            }
            "size" -> host.dispatch(FmEvent.SizeSelected(if (first == "desk") FigureSize.DESK else FigureSize.FULL))
            "mode", "content" -> host.dispatch(FmEvent.ContentSelected(if (first == "still") Content.STILL else Content.FIGURE))
            "lang" -> host.dispatch(FmEvent.LanguageSelected(LanguagePref.fromTag(first)))
            "order" -> host.dispatch(FmEvent.ShuffleSelected(first != "seq"))
            "duration", "dur" -> host.dispatch(FmEvent.DurationSelected(PoseDuration.fromTag(first) ?: return FmLog.i("CMD duration needs 30|60|300|inf")))
            "stick" -> host.dispatch(FmEvent.DebugStick(if (off()) null else num(0)?.coerceIn(-1f, 1f)))
            "dwell" -> host.dispatch(FmEvent.DebugDwell(if (off()) null else num(0)?.coerceIn(0f, 1.5f)))
            "yaw" -> host.dispatch(FmEvent.DebugYaw(num(0) ?: 0f))
            "offset" -> host.dispatch(FmEvent.DebugOffset(num(0) ?: 0f, num(1) ?: 0f))
            "drop" -> {
                // a drag of the figure that ends where it stands now: the drop snap rule runs (design I9)
                host.dispatch(FmEvent.DragStarted)
                host.dispatch(FmEvent.DragEnded)
            }
            "nofloor" -> host.dispatch(FmEvent.DebugNoFloor(first != "0" && first != "off"))
            "blocked" -> host.dispatch(FmEvent.DebugBlocked(first != "0" && first != "off"))
            "place" -> {
                val x = num(0) ?: return FmLog.i("CMD place needs x,z[,table]")
                val z = num(1) ?: return FmLog.i("CMD place needs x,z[,table]")
                FmLog.i(scene?.placeAt(x, z, args.getOrNull(2)?.lowercase(Locale.US) == "table") ?: "CMD place: no scene")
            }
            "pitch" -> host.dispatch(FmEvent.DebugPitch(if (off()) null else num(0)))
            "lux" -> host.dispatch(FmEvent.DebugLux(num(0) ?: 10_000f, num(1) ?: 0f))
            "start", "pause" -> host.dispatch(FmEvent.StartPauseClicked)
            "next" -> host.dispatch(FmEvent.NextClicked("debug"))
            "prev" -> host.dispatch(FmEvent.PrevClicked)
            "menu", "more" -> host.dispatch(FmEvent.MoreClicked)
            "relocate" -> host.dispatch(FmEvent.RelocateClicked)
            "exit" -> host.dispatch(FmEvent.ExitClicked)
            "ui" -> {
                val control = args.getOrNull(0) ?: return FmLog.i("CMD ui needs a control name")
                FmLog.i(if (host.pressControl(control)) "CMD ui $control pressed" else "CMD ui $control not composed (open its panel first)")
            }
            "dump" -> {
                val json = host.dumpJson()
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
                FmLog.i("DUMP $json")
            }
            "hmd" -> FmLog.i(scene?.poseLine() ?: "POSE no scene")
            "panels", "bounds" -> scene?.panelLines()?.forEach { FmLog.i(it) } ?: FmLog.i("PANEL no scene")
            "planes" -> scene?.planesSummary()?.forEach { FmLog.i(it) } ?: FmLog.i("PLANE no scene")
            "figure" -> FmLog.i(scene?.figureLine() ?: "FIGURE no scene")
            "park" -> FmLog.i(scene?.parkPanel() ?: "FOLLOW no scene")
            else -> FmLog.i("CMD $name unknown")
        }
    }
}
