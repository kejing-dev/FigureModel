package io.embodit.figuremodel.ui.fm

import io.embodit.figuremodel.domain.model.BlockReason
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.LanguagePref
import io.embodit.figuremodel.domain.model.LightPreset
import io.embodit.figuremodel.domain.model.PoseDuration
import io.embodit.figuremodel.domain.model.Poses
import io.embodit.figuremodel.domain.model.Settings
import io.embodit.figuremodel.domain.model.StillLife
import io.embodit.figuremodel.domain.model.Vec3

/** Main states (design `stateModel.rule`): S1, S2, S3, S4, S7, S15, S16. */
enum class Phase(val id: String) {
    STARTING("S1"),
    FINDING("S2"),
    READY("S3"),
    DRAWING("S4"),
    PAUSED("S7"),
    NO_FLOOR("S15"),
    BLOCKED("S16"),
}

/** The stand ring: floor (or table) point, arrow heading (yaw of the figure's front), and the light frame. */
data class Placement(val ring: Vec3, val ringYaw: Float, val onTable: Boolean, val front0: Vec3, val serial: Int)

/** Where the figure (or the pedestal / still life) stands and which way it faces. */
data class Stand(val position: Vec3, val yaw: Float)

/** Two-second lines that cover the hint line (design `rules.hintPriority`). */
enum class Flash {
    SNAPPED,
    BACK_TO_FLOOR,
    FLOOR_ONLY,
}

enum class SoundKind {
    TICK,
    CHANGE,
    SNAP,
    FAIL,
}

/** Why the scene must (re)place the ring automatically. */
enum class PlaceReason {
    FIRST,
    RESUME,
    BACK_TO_FLOOR,
}

data class FmUiState(
    val phase: Phase = Phase.STARTING,
    val sceneReady: Boolean = false,
    /** Start was pressed at least once (design S3.reach: from then on the app never returns to S3). */
    val started: Boolean = false,
    val settings: Settings = Settings(),
    val poseIndex: Int = 0,
    val posePosition: Int = 1,
    val stillIndex: Int = 0,
    val stillPosition: Int = 1,
    /** Bumped whenever the shown pose / still-life set changes (the scene eases to it). */
    val poseSerial: Int = 0,
    /** Remaining time shown on the panel, ms; for INF it is 0. */
    val remainingMs: Long = 30_000L,
    /** While the clock runs: elapsedRealtime at which the current pose ends. */
    val endsAtMs: Long? = null,
    /** Until when the 0.6 s pose transition (S6) runs. */
    val transitionUntilMs: Long = 0L,
    /** Debug `freeze=1`: the clock and the transition stand still (transition held half way). */
    val frozen: Boolean = false,
    val placement: Placement? = null,
    val stand: Stand? = null,
    val floorY: Float = 0f,
    val hasFloor: Boolean = false,
    /** The scene places the ring as soon as it has a floor and a head pose. */
    val placeRequest: PlaceReason? = null,
    val placedAtMs: Long = 0L,
    val dragging: Boolean = false,
    val turning: Boolean = false,
    val lighting: Boolean = false,
    val dwelling: Boolean = false,
    val menuOpen: Boolean = false,
    val relocating: Boolean = false,
    val flash: Flash? = null,
    val flashUntilMs: Long = 0L,
    val ringFlashSerial: Int = 0,
    val arrowFlashSerial: Int = 0,
    val blockReason: BlockReason? = null,
    /** Debug `nofloor=1`: S15 stays even if a floor turns up. */
    val forcedNoFloor: Boolean = false,
    /** Debug `pitch=`: the panel behaves as if the head were pitched this much (degrees). */
    val debugPitchDeg: Float? = null,
    /** Debug `stick=`: a held stick value. */
    val debugStick: Float? = null,
    /** Debug `dwell=`: a held dwell progress, seconds. */
    val debugDwell: Float? = null,
    val lightLux: Float = DEFAULT_LUX,
    val fillLux: Float = DEFAULT_FILL_LUX,
) {
    val duration: PoseDuration
        get() = settings.duration

    val durationMs: Long
        get() = (settings.duration.seconds ?: 0) * 1000L

    val language: LanguagePref
        get() = settings.language

    val content: Content
        get() = settings.content

    val size: FigureSize
        get() = settings.size

    /** Desk tier: pedestal (or table) objects, the smaller orbit and disc offsets. */
    val deskTier: Boolean
        get() = settings.size == FigureSize.DESK || settings.content == Content.STILL

    val running: Boolean
        get() = phase == Phase.DRAWING && endsAtMs != null

    fun inTransition(now: Long): Boolean = now < transitionUntilMs

    /** Panel countdown seconds (rounded up). */
    val remainingSeconds: Int
        get() = ((remainingMs + 999L) / 1000L).toInt()

    val lastFive: Boolean
        get() = phase == Phase.DRAWING && settings.duration != PoseDuration.INF && remainingMs in 1..5000L

    /** The 3D content is in the room. */
    val placed: Boolean
        get() = placement != null && stand != null && phase != Phase.FINDING && phase != Phase.STARTING && phase != Phase.NO_FLOOR && phase != Phase.BLOCKED

    /** Panel controls that need a figure (segments, previous / next). */
    val controlsEnabled: Boolean
        get() = phase == Phase.READY || phase == Phase.DRAWING || phase == Phase.PAUSED

    val discShown: Boolean
        get() = placed && controlsEnabled && !dragging && !turning && !lighting && !menuOpen && !relocating

    fun flashActive(now: Long): Flash? = if (flash != null && now < flashUntilMs) flash else null

    val poseName: Pair<String, String>
        get() = Poses.all[poseIndex].let { it.zh to it.en }

    val setCount: Int
        get() = if (settings.content == Content.STILL) StillLife.count else Poses.count

    /** The design's state id of the moment, overlays first (S18, S14, S8, S9, S10, S11, S6, S5, main). */
    fun stateId(now: Long): String =
        when {
            relocating -> "S18"
            menuOpen -> "S14"
            dragging -> "S8"
            turning -> "S9"
            lighting -> "S10"
            dwelling -> "S11"
            placed && inTransition(now) -> "S6"
            lastFive -> "S5"
            else -> phase.id
        }

    /** Tags that stack on any state: S12 still life, S13 desk figure, S17 English. */
    fun stateTags(): String =
        buildString {
            if (settings.content == Content.STILL) append(" +S12")
            if (settings.size == FigureSize.DESK) append(" +S13")
            if (settings.language == LanguagePref.EN) append(" +S17")
        }

    companion object {
        const val DEFAULT_LUX = 10_000f
        /** Optional fill from the other side (debug `lux=main,fill`); off: the passthrough light estimate already lights the dark side. */
        const val DEFAULT_FILL_LUX = 0f
    }
}

sealed interface FmEvent {
    // ---- scene
    data object SceneReady : FmEvent

    data class Tick(val nowMs: Long) : FmEvent

    data class FloorChanged(val hasFloor: Boolean, val floorY: Float) : FmEvent

    data class Placed(val ring: Vec3, val yaw: Float, val onTable: Boolean, val front0: Vec3, val reason: PlaceReason) : FmEvent

    data class HeadSample(val position: Vec3, val forward: Vec3) : FmEvent

    data class BlockChanged(val reason: BlockReason?) : FmEvent

    data object DragStarted : FmEvent

    data class DragMoved(val delta: Vec3) : FmEvent

    data object DragEnded : FmEvent

    data class StickTurn(val deltaRad: Float) : FmEvent

    data object StickReleased : FmEvent

    data object LightDragStarted : FmEvent

    data class LightSet(val azDeg: Float, val elDeg: Float) : FmEvent

    data object LightDragEnded : FmEvent

    data class DwellChanged(val active: Boolean) : FmEvent

    data object TappedOutside : FmEvent

    /** A tap during S18 on the floor ([table] false) or a table. */
    data class RelocateTap(val point: Vec3, val table: Boolean) : FmEvent

    // ---- panel
    data object StartPauseClicked : FmEvent

    /** [source]: panel, disc, dwell, button, timer, debug. */
    data class NextClicked(val source: String) : FmEvent

    data object PrevClicked : FmEvent

    data class DurationSelected(val duration: PoseDuration) : FmEvent

    data object MoreClicked : FmEvent

    data class LightPresetSelected(val preset: LightPreset) : FmEvent

    data class OrbShownSelected(val shown: Boolean) : FmEvent

    data class ShuffleSelected(val shuffle: Boolean) : FmEvent

    data class ContentSelected(val content: Content) : FmEvent

    data class SizeSelected(val size: FigureSize) : FmEvent

    data class SoundSelected(val on: Boolean) : FmEvent

    data class LanguageSelected(val language: LanguagePref) : FmEvent

    data object RelocateClicked : FmEvent

    data object CancelRelocateClicked : FmEvent

    data object RetryClicked : FmEvent

    data object ExitClicked : FmEvent

    // ---- lifecycle
    data object WentBackground : FmEvent

    data object CameForeground : FmEvent

    // ---- debug (QA hooks)
    data class DebugState(val id: String) : FmEvent

    data class DebugPose(val n: Int) : FmEvent

    data class DebugTime(val seconds: Float) : FmEvent

    data class DebugFreeze(val on: Boolean) : FmEvent

    data class DebugNoFloor(val on: Boolean) : FmEvent

    data class DebugBlocked(val on: Boolean) : FmEvent

    data class DebugPlace(val x: Float, val z: Float, val table: Boolean) : FmEvent

    data class DebugPitch(val deg: Float?) : FmEvent

    data class DebugStick(val value: Float?) : FmEvent

    data class DebugDwell(val seconds: Float?) : FmEvent

    data class DebugLux(val main: Float, val fill: Float) : FmEvent

    data class DebugYaw(val deg: Float) : FmEvent

    data class DebugOffset(val dx: Float, val dz: Float) : FmEvent
}

sealed interface FmEffect {
    data object ExitApp : FmEffect

    data class Sound(val kind: SoundKind) : FmEffect
}
