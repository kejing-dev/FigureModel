package io.embodit.figuremodel.ui.fm

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.embodit.figuremodel.data.SettingsStore
import io.embodit.figuremodel.domain.model.BlockReason
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.LanguagePref
import io.embodit.figuremodel.domain.model.LightPreset
import io.embodit.figuremodel.domain.model.Mannequin
import io.embodit.figuremodel.domain.model.PoseDuration
import io.embodit.figuremodel.domain.model.Poses
import io.embodit.figuremodel.domain.model.Settings
import io.embodit.figuremodel.domain.model.StillLife
import io.embodit.figuremodel.domain.model.Vec3
import io.embodit.figuremodel.domain.usecase.LightRule
import io.embodit.figuremodel.domain.usecase.PoseSequence
import io.embodit.figuremodel.domain.usecase.angleDiff
import io.embodit.figuremodel.domain.usecase.flat
import io.embodit.figuremodel.domain.usecase.frontOf
import io.embodit.figuremodel.domain.usecase.yawFacing
import io.embodit.figuremodel.platform.FmGraph
import io.embodit.figuremodel.platform.FmLog
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The whole state machine of the app (design `states`, `interactions`, `rules`). Panel controls, scene
 * callbacks (taps, drags, the stick, the dwell disc, floor, head, block reason, the 100 ms tick) and the
 * debug hooks all arrive through [onEvent]; the clock runs on the scene's tick, not on a coroutine.
 * Main thread only.
 */
class FmViewModel(private val store: SettingsStore) : ViewModel() {

    private val poseSeq = PoseSequence(Poses.count)
    private val stillSeq = PoseSequence(StillLife.count)
    private val _state: MutableStateFlow<FmUiState>
    val state: StateFlow<FmUiState>
    private val _effects = MutableSharedFlow<FmEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<FmEffect> = _effects

    private var head: Vec3? = null
    private var headForward: Vec3 = Vec3.AHEAD
    private var findingSinceMs = 0L
    private var startingUntilMs = 0L
    private var pendingPlace: PlaceReason? = null
    private var pendingPlaceAtMs = 0L
    private var wentBackground = false
    private var lastLogged = ""
    private var lastTickSecond = -1

    init {
        val settings = store.load()
        poseSeq.start(settings.shuffle)
        stillSeq.start(settings.shuffle)
        _state =
            MutableStateFlow(
                FmUiState(
                    settings = settings,
                    poseIndex = poseSeq.current,
                    posePosition = poseSeq.position,
                    stillIndex = stillSeq.current,
                    stillPosition = stillSeq.position,
                    remainingMs = (settings.duration.seconds ?: 0) * 1000L,
                )
            )
        state = _state.asStateFlow()
        Poses.resolved.size
        FmLog.i("POSES settled n=${Poses.count} knee_fix=${Mannequin.kneeFixes}")
        FmLog.i("SETTINGS $settings")
    }

    private val now: Long
        get() = SystemClock.elapsedRealtime()

    private inline fun update(f: (FmUiState) -> FmUiState) {
        _state.value = f(_state.value)
    }

    private fun emit(effect: FmEffect) {
        _effects.tryEmit(effect)
    }

    private fun sound(kind: SoundKind) {
        if (_state.value.settings.sound) {
            FmLog.i("SOUND ${kind.name.lowercase(Locale.US)}")
            emit(FmEffect.Sound(kind))
        }
    }

    private fun settings(f: (Settings) -> Settings) {
        val next = f(_state.value.settings)
        update { it.copy(settings = next) }
        store.save(next)
    }

    fun onEvent(event: FmEvent) {
        if (event !is FmEvent.Tick && event !is FmEvent.HeadSample && event !is FmEvent.DragMoved && event !is FmEvent.StickTurn && event !is FmEvent.LightSet) {
            FmLog.i("EVENT ${event.javaClass.simpleName}")
        }
        handle(event)
        logState()
    }

    private fun handle(event: FmEvent) {
        val s = _state.value
        when (event) {
            FmEvent.SceneReady -> {
                update { it.copy(sceneReady = true) }
                if (s.phase == Phase.STARTING) startingUntilMs = now + STARTING_MIN_MS
            }
            is FmEvent.Tick -> tick(event.nowMs)
            is FmEvent.FloorChanged -> {
                update { it.copy(hasFloor = event.hasFloor, floorY = event.floorY) }
                if (event.hasFloor && s.phase == Phase.NO_FLOOR && !s.forcedNoFloor) toFinding(if (s.started) PlaceReason.RESUME else PlaceReason.FIRST)
            }
            is FmEvent.Placed -> placed(event)
            is FmEvent.HeadSample -> {
                head = event.position
                headForward = event.forward
            }
            is FmEvent.BlockChanged -> {
                val reason = event.reason ?: return
                if (s.phase == Phase.BLOCKED) return
                pauseClock()
                update { it.copy(phase = Phase.BLOCKED, blockReason = reason, menuOpen = false, relocating = false, dragging = false, turning = false, lighting = false) }
            }

            // ---- figure: drag, stick, light, dwell
            FmEvent.DragStarted -> if (s.placed && !s.menuOpen && !s.relocating && !s.turning) update { it.copy(dragging = true) }
            is FmEvent.DragMoved -> {
                val st = s.stand ?: return
                if (!s.dragging) return
                update { it.copy(stand = st.copy(position = Vec3(st.position.x + event.delta.x, st.position.y, st.position.z + event.delta.z))) }
            }
            FmEvent.DragEnded -> if (s.dragging) dropped()
            is FmEvent.StickTurn -> {
                val st = s.stand ?: return
                if (!s.placed || s.dragging || s.relocating || s.menuOpen) return
                update { it.copy(turning = true, stand = st.copy(yaw = st.yaw + event.deltaRad)) }
            }
            FmEvent.StickReleased -> if (s.turning) stickReleased()
            FmEvent.LightDragStarted -> if (s.placed && !s.menuOpen && !s.relocating) update { it.copy(lighting = true) }
            is FmEvent.LightSet ->
                settings { it.copy(lightAzDeg = LightRule.wrapAz(event.azDeg), lightElDeg = event.elDeg.coerceIn(LightRule.EL_MIN, LightRule.EL_MAX), lightPreset = null) }
            FmEvent.LightDragEnded -> if (s.lighting) update { it.copy(lighting = false) }
            is FmEvent.DwellChanged -> if (s.dwelling != event.active) update { it.copy(dwelling = event.active) }
            FmEvent.TappedOutside -> if (s.menuOpen) update { it.copy(menuOpen = false) }
            is FmEvent.RelocateTap -> relocateTap(event)

            // ---- panel
            FmEvent.StartPauseClicked -> startPause()
            is FmEvent.NextClicked -> changePose(forward = true, source = event.source)
            FmEvent.PrevClicked -> changePose(forward = false, source = "panel")
            is FmEvent.DurationSelected -> durationSelected(event.duration)
            FmEvent.MoreClicked -> if (!s.relocating && s.phase != Phase.STARTING) update { it.copy(menuOpen = !it.menuOpen) }
            is FmEvent.LightPresetSelected -> lightPreset(event.preset)
            is FmEvent.OrbShownSelected -> settings { it.copy(orbShown = event.shown) }
            is FmEvent.ShuffleSelected -> {
                poseSeq.setShuffle(event.shuffle)
                stillSeq.setShuffle(event.shuffle)
                settings { it.copy(shuffle = event.shuffle) }
                update { it.copy(posePosition = poseSeq.position, stillPosition = stillSeq.position) }
                FmLog.i("ORDER ${if (event.shuffle) "shuffle" else "seq"} upcoming=${poseSeq.upcoming().map { it + 1 }}")
            }
            is FmEvent.ContentSelected -> contentOrSize(s.settings.copy(content = event.content))
            is FmEvent.SizeSelected -> contentOrSize(s.settings.copy(size = event.size))
            is FmEvent.SoundSelected -> settings { it.copy(sound = event.on) }
            is FmEvent.LanguageSelected -> settings { it.copy(language = event.language) }
            FmEvent.RelocateClicked -> relocate()
            FmEvent.CancelRelocateClicked -> if (s.relocating) endRelocate()
            FmEvent.RetryClicked -> retry()
            FmEvent.ExitClicked -> emit(FmEffect.ExitApp)

            // ---- lifecycle (design I19)
            FmEvent.WentBackground -> {
                wentBackground = true
                pauseClock()
                update {
                    it.copy(
                        phase = if (it.phase == Phase.BLOCKED) Phase.BLOCKED else Phase.FINDING,
                        placement = null,
                        stand = null,
                        placeRequest = null,
                        menuOpen = false,
                        relocating = false,
                        dragging = false,
                        turning = false,
                        lighting = false,
                        dwelling = false,
                    )
                }
            }
            FmEvent.CameForeground -> {
                if (!wentBackground) return
                wentBackground = false
                if (s.phase == Phase.FINDING) toFinding(if (s.started) PlaceReason.RESUME else PlaceReason.FIRST)
            }

            is FmEvent.DebugState -> debugState(event.id.uppercase(Locale.US))
            is FmEvent.DebugPose -> {
                val i = (event.n - 1).coerceIn(0, Poses.count - 1)
                if (s.content == Content.STILL) settings { it.copy(content = Content.FIGURE) }
                poseSeq.jump(i)
                showPose(source = "debug")
            }
            is FmEvent.DebugTime -> {
                val ms = (event.seconds * 1000f).toLong().coerceAtLeast(0L)
                update { it.copy(phase = Phase.DRAWING, started = true, remainingMs = ms, endsAtMs = now + ms, frozen = true, transitionUntilMs = 0L) }
            }
            is FmEvent.DebugFreeze -> {
                if (event.on) update { it.copy(frozen = true) }
                else
                    update {
                        it.copy(
                            frozen = false,
                            transitionUntilMs = if (it.transitionUntilMs > now + TRANSITION_MS) now else it.transitionUntilMs,
                            endsAtMs = if (it.phase == Phase.DRAWING && it.duration != PoseDuration.INF) now + it.remainingMs else it.endsAtMs,
                        )
                    }
            }
            is FmEvent.DebugNoFloor -> {
                if (event.on) {
                    pauseClock()
                    update { it.copy(phase = Phase.NO_FLOOR, forcedNoFloor = true, placement = null, stand = null, placeRequest = null, menuOpen = false, relocating = false) }
                } else {
                    update { it.copy(forcedNoFloor = false) }
                    retry()
                }
            }
            is FmEvent.DebugBlocked -> {
                if (event.on) handle(FmEvent.BlockChanged(BlockReason.NOT_IN_FULL_SPACE)) else retry()
            }
            is FmEvent.DebugPlace -> Unit // resolved by the scene (it knows the planes) and comes back as RelocateTap
            is FmEvent.DebugPitch -> update { it.copy(debugPitchDeg = event.deg) }
            is FmEvent.DebugStick -> update { it.copy(debugStick = event.value) }
            is FmEvent.DebugDwell -> update { it.copy(debugDwell = event.seconds) }
            is FmEvent.DebugLux -> update { it.copy(lightLux = event.main, fillLux = event.fill) }
            is FmEvent.DebugYaw -> {
                val p = s.placement ?: return
                val st = s.stand ?: return
                update { it.copy(stand = st.copy(yaw = p.ringYaw + Math.toRadians(event.deg.toDouble()).toFloat())) }
            }
            is FmEvent.DebugOffset -> {
                val st = s.stand ?: return
                update { it.copy(stand = st.copy(position = st.position + Vec3(event.dx, 0f, event.dz))) }
            }
        }
    }

    // =========================================================================================
    // clock
    // =========================================================================================

    private fun tick(t: Long) {
        val s = _state.value
        if (s.flash != null && t >= s.flashUntilMs) update { it.copy(flash = null) }
        when (s.phase) {
            Phase.STARTING -> if (s.sceneReady && t >= startingUntilMs) toFinding(if (s.started) PlaceReason.RESUME else PlaceReason.FIRST)
            Phase.FINDING -> {
                val pending = pendingPlace
                if (pending != null && t >= pendingPlaceAtMs) {
                    pendingPlace = null
                    update { it.copy(placeRequest = pending) }
                }
                if (s.placeRequest != null && !s.hasFloor && t - findingSinceMs > NO_FLOOR_AFTER_MS) {
                    FmLog.i("FLOOR none after ${NO_FLOOR_AFTER_MS / 1000} s")
                    update { it.copy(phase = Phase.NO_FLOOR, placeRequest = null) }
                }
            }
            else -> Unit
        }
        if (s.frozen || s.phase != Phase.DRAWING || s.relocating) return
        val ends = s.endsAtMs ?: return
        val remaining = if (t < s.transitionUntilMs) s.durationMs else ends - t
        if (remaining <= 0L) {
            changePose(forward = true, source = "timer")
            return
        }
        val sec = ((remaining + 999L) / 1000L).toInt()
        if (sec != lastTickSecond) {
            lastTickSecond = sec
            if (sec <= 5 && t >= s.transitionUntilMs) sound(SoundKind.TICK)
        }
        // the panel shows whole seconds: the state changes once a second, the ring arc reads endsAtMs itself
        if (s.remainingSeconds != sec) update { it.copy(remainingMs = remaining) }
    }

    /** Stops the clock where it is (pause, relocation, background). */
    private fun pauseClock() {
        val s = _state.value
        val ends = s.endsAtMs ?: return
        val t = now
        val remaining = if (t < s.transitionUntilMs) s.durationMs else (ends - t).coerceAtLeast(0L)
        update { it.copy(endsAtMs = null, remainingMs = remaining, phase = if (it.phase == Phase.DRAWING) Phase.PAUSED else it.phase) }
    }

    private fun startPause() {
        val s = _state.value
        if (s.relocating) {
            endRelocate()
            return
        }
        when (s.phase) {
            Phase.READY, Phase.PAUSED -> {
                val ms = if (s.phase == Phase.READY || s.remainingMs <= 0L) s.durationMs else s.remainingMs
                update { it.copy(phase = Phase.DRAWING, started = true, remainingMs = ms, endsAtMs = if (it.duration == PoseDuration.INF) null else now + ms, frozen = false) }
                lastTickSecond = -1
            }
            Phase.DRAWING -> pauseClock()
            Phase.NO_FLOOR -> retry()
            else -> Unit
        }
    }

    private fun durationSelected(d: PoseDuration) {
        settings { it.copy(duration = d) }
        val s = _state.value
        val ms = s.durationMs
        update { it.copy(remainingMs = ms, endsAtMs = if (it.phase == Phase.DRAWING && d != PoseDuration.INF) now + ms else null) }
        lastTickSecond = -1
        FmLog.i("TIMER duration=${d.name} ms=$ms")
    }

    // =========================================================================================
    // poses (design I3 / I4 / I5 / I6)
    // =========================================================================================

    private fun changePose(forward: Boolean, source: String) {
        val s = _state.value
        if (!s.controlsEnabled || s.relocating) return
        if (source == "disc" || source == "dwell" || source == "button") {
            if (s.dragging || s.turning || s.lighting || s.menuOpen) return
        }
        val seq = if (s.content == Content.STILL) stillSeq else poseSeq
        if (forward) seq.next() else seq.previous()
        showPose(source)
    }

    private fun showPose(source: String) {
        val t = now
        update {
            val dur = it.durationMs
            it.copy(
                poseIndex = poseSeq.current,
                posePosition = poseSeq.position,
                stillIndex = stillSeq.current,
                stillPosition = stillSeq.position,
                poseSerial = it.poseSerial + 1,
                transitionUntilMs = t + TRANSITION_MS,
                remainingMs = dur,
                endsAtMs = if (it.phase == Phase.DRAWING && it.duration != PoseDuration.INF) t + TRANSITION_MS + dur else null,
                dwelling = false,
            )
        }
        lastTickSecond = -1
        val s = _state.value
        FmLog.i(
            if (s.content == Content.STILL) "POSE still=${s.stillIndex + 1} pos=${s.stillPosition}/${StillLife.count} source=$source"
            else "POSE n=${s.poseIndex + 1} ${Poses.all[s.poseIndex].en} pos=${s.posePosition}/${Poses.count} source=$source"
        )
        sound(SoundKind.CHANGE)
    }

    // =========================================================================================
    // placement, drag + snap (design I1 / I8 / I9 / I17)
    // =========================================================================================

    private fun toFinding(reason: PlaceReason) {
        findingSinceMs = now
        pendingPlace = null
        update { it.copy(phase = Phase.FINDING, placeRequest = reason, blockReason = null, placement = null, stand = null, menuOpen = false) }
    }

    private fun placed(e: FmEvent.Placed) {
        val s = _state.value
        val placement = Placement(e.ring, e.yaw, e.onTable, e.front0, (s.placement?.serial ?: 0) + 1)
        val phase =
            when (e.reason) {
                PlaceReason.BACK_TO_FLOOR -> s.phase
                else -> if (s.started) Phase.PAUSED else Phase.READY
            }
        update {
            it.copy(
                placement = placement,
                stand = Stand(e.ring, e.yaw),
                placeRequest = null,
                phase = phase,
                placedAtMs = now,
                flash = if (e.reason == PlaceReason.BACK_TO_FLOOR) Flash.BACK_TO_FLOOR else it.flash,
                flashUntilMs = if (e.reason == PlaceReason.BACK_TO_FLOOR) now + FLASH_MS else it.flashUntilMs,
            )
        }
        FmLog.i("PLACED reason=${e.reason} ring=${FmLog.pos(e.ring)} yaw=${deg(e.yaw)} table=${e.onTable}")
    }

    private fun dropped() {
        val s = _state.value
        val p = s.placement
        val st = s.stand
        if (p == null || st == null) {
            update { it.copy(dragging = false) }
            return
        }
        val d = flatDistance(st.position, p.ring)
        if (d <= SNAP_M) {
            val yawSnap = abs(angleDiff(st.yaw, p.ringYaw)) <= rad(SNAP_YAW_DEG)
            update {
                it.copy(
                    dragging = false,
                    stand = Stand(p.ring, if (yawSnap) p.ringYaw else st.yaw),
                    ringFlashSerial = it.ringFlashSerial + 1,
                    flash = Flash.SNAPPED,
                    flashUntilMs = now + FLASH_MS,
                )
            }
            FmLog.i("SNAP drop d=${FmLog.f(d, 3)} yaw_snap=$yawSnap")
            sound(SoundKind.SNAP)
        } else {
            update { it.copy(dragging = false) }
            FmLog.i("SNAP none d=${FmLog.f(d, 3)}")
        }
    }

    private fun stickReleased() {
        val s = _state.value
        val p = s.placement
        val st = s.stand
        if (p == null || st == null) {
            update { it.copy(turning = false) }
            return
        }
        val onRing = flatDistance(st.position, p.ring) <= ON_RING_M
        val diff = angleDiff(st.yaw, p.ringYaw)
        if (onRing && abs(diff) <= rad(STICK_SNAP_DEG)) {
            update { it.copy(turning = false, stand = st.copy(yaw = p.ringYaw), arrowFlashSerial = it.arrowFlashSerial + 1) }
            FmLog.i("SNAP stick diff=${deg(diff)}")
            sound(SoundKind.SNAP)
        } else {
            update { it.copy(turning = false) }
            FmLog.i("STICK release yaw=${deg(st.yaw)} diff=${deg(diff)} on_ring=$onRing")
        }
    }

    private fun relocate() {
        val s = _state.value
        if (!s.placed) return
        pauseClock()
        update { it.copy(menuOpen = false, relocating = true, dragging = false, turning = false, lighting = false, dwelling = false) }
    }

    private fun endRelocate() {
        update { it.copy(relocating = false, phase = if (it.started) Phase.PAUSED else Phase.READY) }
    }

    private fun relocateTap(e: FmEvent.RelocateTap) {
        val s = _state.value
        if (!s.relocating) return
        if (e.table && !s.deskTier) {
            update { it.copy(flash = Flash.FLOOR_ONLY, flashUntilMs = now + FLASH_MS) }
            sound(SoundKind.FAIL)
            FmLog.i("RELOCATE refused table (life-size figure)")
            return
        }
        val toward = head?.let { flat(it - e.point) } ?: flat(-headForward)
        val yaw = yawFacing(toward)
        update {
            it.copy(
                placement = Placement(e.point, yaw, e.table, frontOf(yaw), (it.placement?.serial ?: 0) + 1),
                stand = Stand(e.point, yaw),
                relocating = false,
                phase = if (it.started) Phase.PAUSED else Phase.READY,
                placedAtMs = now,
            )
        }
        FmLog.i("RELOCATE ring=${FmLog.pos(e.point)} table=${e.table}")
        sound(SoundKind.SNAP)
    }

    private fun contentOrSize(next: Settings) {
        val s = _state.value
        val contentChanged = next.content != s.settings.content
        settings { next }
        if (contentChanged) {
            update { it.copy(poseSerial = it.poseSerial + 1, transitionUntilMs = now + TRANSITION_MS) }
            FmLog.i("CONTENT ${next.content}")
        }
        if (next.size != s.settings.size) FmLog.i("SIZE ${next.size}")
        val p = s.placement
        if (p != null && p.onTable && next.size == FigureSize.FULL && next.content == Content.FIGURE) {
            update { it.copy(placeRequest = PlaceReason.BACK_TO_FLOOR) }
        }
    }

    private fun lightPreset(preset: LightPreset) {
        val s = _state.value
        val p = s.placement
        val st = s.stand
        val (az, el) =
            if (p != null && st != null) {
                val toward = head?.let { flat(it - st.position) } ?: p.front0
                LightRule.preset(preset, p.front0, toward, s.settings.lightAzDeg)
            } else {
                when (preset) {
                    LightPreset.LEFT -> -45f to 45f
                    LightPreset.RIGHT -> 45f to 45f
                    LightPreset.FRONT -> 0f to 30f
                    LightPreset.BACK -> 180f to 60f
                    LightPreset.TOP -> s.settings.lightAzDeg to 80f
                }
            }
        settings { it.copy(lightPreset = preset, lightAzDeg = LightRule.wrapAz(az), lightElDeg = el) }
        FmLog.i("LIGHT preset=$preset az=${FmLog.f(az, 1)} el=${FmLog.f(el, 1)}")
    }

    private fun retry() {
        val s = _state.value
        when (s.phase) {
            Phase.BLOCKED -> {
                startingUntilMs = now + RETRY_STARTING_MS
                update { it.copy(phase = Phase.STARTING, blockReason = null) }
            }
            Phase.NO_FLOOR -> {
                update { it.copy(forcedNoFloor = false) }
                toFinding(if (s.started) PlaceReason.RESUME else PlaceReason.FIRST)
            }
            else -> Unit
        }
    }

    // =========================================================================================
    // debug: straight to a designed state (design `qaHooks`)
    // =========================================================================================

    private fun debugState(id: String) {
        val t = now
        fun clearOverlays() =
            update {
                it.copy(
                    dragging = false, turning = false, lighting = false, dwelling = false, menuOpen = false, relocating = false, frozen = false,
                    debugDwell = null, debugStick = null, debugPitchDeg = null, forcedNoFloor = false,
                    transitionUntilMs = if (it.transitionUntilMs > now + TRANSITION_MS) 0L else it.transitionUntilMs,
                )
            }
        fun needPlaced(): Boolean {
            if (_state.value.placement != null && _state.value.stand != null) return true
            FmLog.i("CMD state $id needs the figure placed first (wait for S3)")
            return false
        }
        fun ready() =
            update {
                it.copy(phase = Phase.READY, started = false, remainingMs = it.durationMs, endsAtMs = null, transitionUntilMs = 0L, stand = it.placement?.let { p -> Stand(p.ring, p.ringYaw) } ?: it.stand)
            }
        fun drawing(remaining: Long = _state.value.durationMs) =
            update { it.copy(phase = Phase.DRAWING, started = true, remainingMs = remaining, endsAtMs = if (it.duration == PoseDuration.INF) null else now + remaining, transitionUntilMs = 0L) }
        when (id) {
            "S1" -> {
                clearOverlays()
                startingUntilMs = t + DEBUG_HOLD_MS
                update { it.copy(phase = Phase.STARTING, placement = null, stand = null, placeRequest = null, blockReason = null) }
            }
            "S2" -> {
                clearOverlays()
                findingSinceMs = t
                pendingPlace = if (_state.value.started) PlaceReason.RESUME else PlaceReason.FIRST
                pendingPlaceAtMs = t + DEBUG_HOLD_MS
                update { it.copy(phase = Phase.FINDING, placement = null, stand = null, placeRequest = null, blockReason = null) }
            }
            "S3" -> {
                clearOverlays()
                if (needPlaced()) ready()
            }
            "S4" -> {
                clearOverlays()
                if (needPlaced()) drawing()
            }
            "S5" -> {
                clearOverlays()
                if (needPlaced()) {
                    if (_state.value.duration == PoseDuration.INF) settings { it.copy(duration = PoseDuration.S30) }
                    drawing(4_000L)
                    update { it.copy(frozen = true) }
                }
            }
            "S6" -> {
                clearOverlays()
                if (needPlaced()) {
                    drawing()
                    changePose(forward = true, source = "debug")
                    update { it.copy(frozen = true, transitionUntilMs = Long.MAX_VALUE) }
                }
            }
            "S7" -> {
                clearOverlays()
                if (needPlaced()) update { it.copy(phase = Phase.PAUSED, started = true, endsAtMs = null, remainingMs = (it.durationMs - 3_000L).coerceAtLeast(0L)) }
            }
            "S8" -> {
                clearOverlays()
                if (needPlaced()) {
                    val p = _state.value.placement!!
                    val side = frontOf(p.ringYaw + (Math.PI / 2).toFloat())
                    update { it.copy(dragging = true, stand = Stand(p.ring + side * 0.45f, p.ringYaw)) }
                }
            }
            "S9" -> {
                clearOverlays()
                if (needPlaced()) {
                    val p = _state.value.placement!!
                    update { it.copy(turning = true, stand = Stand(p.ring, p.ringYaw - rad(75f))) }
                }
            }
            "S10" -> {
                clearOverlays()
                if (needPlaced()) {
                    lightPreset(LightPreset.RIGHT)
                    update { it.copy(lighting = true) }
                }
            }
            "S11" -> {
                clearOverlays()
                if (needPlaced()) update { it.copy(debugDwell = 0.9f) }
            }
            "S12" -> {
                update { it.copy(debugDwell = null, dwelling = false) }
                contentOrSize(_state.value.settings.copy(content = Content.STILL))
            }
            "S13" -> {
                update { it.copy(debugDwell = null, dwelling = false) }
                contentOrSize(_state.value.settings.copy(size = FigureSize.DESK))
            }
            "S14" -> {
                clearOverlays()
                update { it.copy(menuOpen = true) }
            }
            "S15" -> handle(FmEvent.DebugNoFloor(true))
            "S16" -> handle(FmEvent.DebugBlocked(true))
            "S17" -> {
                update { it.copy(debugDwell = null, dwelling = false) }
                settings { it.copy(language = LanguagePref.EN) }
            }
            "S18" -> {
                clearOverlays()
                if (needPlaced()) relocate()
            }
            "FIGURE", "S12OFF" -> contentOrSize(_state.value.settings.copy(content = Content.FIGURE))
            "FULL", "S13OFF" -> contentOrSize(_state.value.settings.copy(size = FigureSize.FULL))
            "ZH", "S17OFF" -> settings { it.copy(language = LanguagePref.ZH) }
            else -> FmLog.i("CMD state $id unknown (S1..S18)")
        }
    }

    // =========================================================================================
    // logs and dump
    // =========================================================================================

    private fun logState() {
        val s = _state.value
        val t = now
        val id = s.stateId(t)
        val n = if (s.content == Content.STILL) "still=${s.stillIndex + 1}" else "pose=${s.poseIndex + 1}"
        val line = "$id $n t=${if (s.duration == PoseDuration.INF) "inf" else s.remainingSeconds.toString()}${s.stateTags()}"
        val key = "$id $n${s.stateTags()}"
        if (key != lastLogged) {
            lastLogged = key
            FmLog.i("FM-STATE $line")
        }
    }

    fun dumpJson(): String {
        val s = _state.value
        val t = now
        fun v(p: Vec3?) = if (p == null) "null" else String.format(Locale.US, "[%.3f,%.3f,%.3f]", p.x, p.y, p.z)
        return buildString {
            append("{")
            append("\"state\":\"${s.stateId(t)}\",\"tags\":\"${s.stateTags().trim()}\",\"phase\":\"${s.phase.name}\",\"started\":${s.started},")
            append("\"pose\":${s.poseIndex + 1},\"posePos\":${s.posePosition},\"still\":${s.stillIndex + 1},\"stillPos\":${s.stillPosition},")
            append("\"remainingMs\":${s.remainingMs},\"endsIn\":${s.endsAtMs?.let { it - t } ?: "null"},\"frozen\":${s.frozen},")
            append("\"ring\":${v(s.placement?.ring)},\"ringYaw\":${s.placement?.let { deg(it.ringYaw) } ?: "null"},\"onTable\":${s.placement?.onTable ?: false},")
            append("\"stand\":${v(s.stand?.position)},\"standYaw\":${s.stand?.let { deg(it.yaw) } ?: "null"},")
            append("\"floorY\":${FmLog.f(s.floorY, 3)},\"hasFloor\":${s.hasFloor},\"menu\":${s.menuOpen},\"relocating\":${s.relocating},")
            append("\"settings\":\"${s.settings}\",\"upcoming\":\"${poseSeq.upcoming().map { it + 1 }}\",\"head\":${v(head)}")
            append("}")
        }
    }

    private fun flatDistance(a: Vec3, b: Vec3): Float = Vec3(a.x - b.x, 0f, a.z - b.z).length

    private fun rad(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    private fun deg(r: Float): String = FmLog.f(Math.toDegrees(r.toDouble()).toFloat(), 1)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FmViewModel(FmGraph.settings(context.applicationContext)) as T
    }

    companion object {
        const val TRANSITION_MS = 600L
        private const val FLASH_MS = 2_000L
        private const val NO_FLOOR_AFTER_MS = 20_000L
        private const val STARTING_MIN_MS = 600L
        private const val RETRY_STARTING_MS = 900L
        private const val DEBUG_HOLD_MS = 8_000L
        const val SNAP_M = 0.15f
        const val SNAP_YAW_DEG = 20f
        const val STICK_SNAP_DEG = 15f
        const val ON_RING_M = 0.02f
    }
}
