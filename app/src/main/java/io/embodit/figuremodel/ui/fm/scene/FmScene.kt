package io.embodit.figuremodel.ui.fm.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.figuremodel.domain.model.BlockReason
import io.embodit.figuremodel.domain.model.Content
import io.embodit.figuremodel.domain.model.FigureFrame
import io.embodit.figuremodel.domain.model.FigureSize
import io.embodit.figuremodel.domain.model.J
import io.embodit.figuremodel.domain.model.Mannequin
import io.embodit.figuremodel.domain.model.Poses
import io.embodit.figuremodel.domain.model.Quat4
import io.embodit.figuremodel.domain.model.SurfaceKind
import io.embodit.figuremodel.domain.model.Vec3
import io.embodit.figuremodel.domain.usecase.HitSurfaceUseCase
import io.embodit.figuremodel.domain.usecase.LightRule
import io.embodit.figuremodel.domain.usecase.PlaceRule
import io.embodit.figuremodel.domain.usecase.angleDiff
import io.embodit.figuremodel.domain.usecase.flat
import io.embodit.figuremodel.domain.usecase.frontOf
import io.embodit.figuremodel.domain.usecase.yawFacing
import io.embodit.figuremodel.platform.FmGraph
import io.embodit.figuremodel.platform.FmLog
import io.embodit.figuremodel.platform.sense.PlaneTracker
import io.embodit.figuremodel.platform.tracking.HeadPose
import io.embodit.figuremodel.ui.fm.FmEvent
import io.embodit.figuremodel.ui.fm.FmUiState
import io.embodit.figuremodel.ui.fm.FmViewModel
import io.embodit.figuremodel.ui.fm.Phase
import io.embodit.figuremodel.ui.fm.PlaceReason
import io.embodit.figuremodel.ui.fm.debug.FmProbe
import io.embodit.figuremodel.ui.theme.FmColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: FmScene? = null
}

/** The one per-frame ECS system of the app (design note: timers, stick, drag, snap and dwell run here). */
class FmFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the mannequin (or pedestal and still life), the stand ring, the timer ring, the light
 * orb and its directional light, the next disc, the relocation ghost, the floor / table catchers, and the
 * placement of the pre-built panels. Compose state arrives through [render]; per-frame work (pose easing,
 * smoothing, the stick, dwell, the clock tick, panel follow) happens in [onFrame] only and never goes
 * through Compose. Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class FmScene(private val sink: (FmEvent) -> Unit) : FmProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = FmGraph.planes
    private val headTracker = FmGraph.head
    private val controller = FmGraph.controller
    private val hits = HitSurfaceUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var meshes: FmMeshes? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var figure: FigureView? = null
    private var pedestal: PedestalView? = null
    private var still: StillLifeView? = null
    private var ring: RingView? = null
    private var ghostRing: RingView? = null
    private var timerRing: TimerRingView? = null
    private var orb: OrbView? = null
    private var disc: DiscView? = null
    private var light: Entity? = null
    private var fill: Entity? = null
    private var attached = false
    private var sensing = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = FmUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastHeadSentMs = 0L
    private var lastTickMs = 0L
    private var lastFrameMs = 0L
    private var lastFrameDtMs = 0L
    private var lastFloor: Boolean? = null
    private var lastFloorY = Float.NaN
    private var blockSinceMs = NEVER
    private var blockSent = false
    private var placeSent = false

    // shown figure: smoothed towards the state's stand, pose eased towards the state's pose
    private var shownPos: Vec3? = null
    private var shownYaw = 0f
    private var poseFrom: FloatArray = Poses.resolved[0].copyOf()
    private var poseTo: FloatArray = Poses.resolved[0].copyOf()
    private var poseNow: FloatArray = Poses.resolved[0].copyOf()
    private var poseStartMs = NEVER
    private var poseSerialSeen = -1
    private var stoolFrom = 0f
    private var stoolTo = 0f
    private var stillShown = -1
    private var contentShown: Content? = null
    private var contentFadeStartMs = NEVER
    private var figureFadeStartMs = NEVER
    private var placementSeen = -1
    private var ringFlashSeen = 0
    private var ringFlashUntil = 0L
    private var arrowFlashSeen = 0
    private var arrowFlashUntil = 0L

    // drags with the system pointer
    private enum class DragKind { FIGURE, ORB }

    private var drag: DragKind? = null
    private var dragPxToM = 0.001f
    private var orbDragPos: Vec3? = null

    // thumbstick, smoothed to a stop over 0.2 s
    private var stickRate = 0f
    private var stickHeld = false

    // dwell disc
    private var dwellStartMs = NEVER
    private var dwellCoolUntil = 0L
    private var dwellActiveSent = false
    private var discOffset = DISC_OFFSET_M
    private var discOutUntil = 0L
    private var discLeft = false

    // panel follow (yaw only, design `rules.panelFollow`)
    private val eyeSamples = ArrayList<Float>()
    private var eyeHeight = Float.NaN
    private var firstHeadMs = NEVER
    private var lastEyeAdjustMs = 0L
    private var anchorYaw = Float.NaN
    private var anchorHead: Vec3 = Vec3.ZERO
    private var outSinceMs = NEVER
    private var slideFromYaw = 0f
    private var slideFromHead = Vec3.ZERO
    private var slideStartMs = NEVER
    private var dodgeYaw = 0f
    private var dodgePitch = 0f
    private var dodgeClearSince = NEVER
    private var dialogPlaced = false

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin: move the root once so that its
        // local space equals Stage space (Spatial Ruler).
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        FmLog.i("STAGE root offset=${FmLog.pos(offset.domain())}")
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h).also { if (id != PanelSpec.DIALOG) it.autoFace = false }
        }
        startSensing()
        ActiveScene.scene = this
        bindPanels(attachments)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) FmLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Head tracking and plane detection (no hand tracking: it breaks gaze + pinch on the emulator). */
    private fun startSensing() {
        if (sensing) return
        sensing = true
        headTracker.start()
        controller.start()
        planeTracker.start(
            PlaneTracker.Listener { id, surface, _ ->
                if (surface != null) colliders?.upsert(surface) else colliders?.remove(id)
            }
        )
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        lastFloor = null
        lastFloorY = Float.NaN
        hasHead = false
    }

    /** Leaving for the background stops the trackers; coming back starts them again (design `lifecycle`). */
    fun onBackground() {
        stopSensing()
        colliders?.clear()
        colliders?.buildBackstop()
        FmLog.i("SENSING stopped (background)")
    }

    fun onForeground() {
        if (stageRoot == null) return
        startSensing()
        FmLog.i("SENSING started (foreground)")
    }

    /** Materials and meshes, a few at a time: many meshes in one frame stall the emulator (ANR). */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        yield()
        val ms = FmMeshes()
        ms.build(sh) { pace(MESH_GAP_SLOW_MS) }
        meshes = ms
        FmLog.i("SCENE meshes built ms=${SystemClock.elapsedRealtime() - t0}")
        pace(ENTITY_GAP_SLOW_MS)
        figure = FigureView(root, m, ms).also { it.setShown(false) }
        pace(ENTITY_GAP_SLOW_MS)
        pedestal = PedestalView(root, m, ms)
        still = StillLifeView(root, sh, m, ms)
        pace(ENTITY_GAP_SLOW_MS)
        ring = RingView(root, m, ms, ghost = false)
        ghostRing = RingView(root, m, ms, ghost = true)
        pace(ENTITY_GAP_SLOW_MS)
        timerRing = TimerRingView(root, m, ms)
        pace(ENTITY_GAP_SLOW_MS)
        orb = OrbView(root, m, ms)
        disc = DiscView(root, m, ms)
        pace(ENTITY_GAP_SLOW_MS)
        light = Entity().named("keyLight").also {
            it.components.set(DirectionalLightComponent(FmColors.LampLight.color4(), ui.lightLux, false))
            root.addChild(it)
        }
        fill = Entity().named("fillLight").also { root.addChild(it) }
        applyLux(ui)
        attached = true
        FmLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        poseSerialSeen = -1
        render(ui)
        sink(FmEvent.SceneReady)
    }

    /**
     * Gives the main thread back between expensive steps: one frame when frames are fast (headset), [slowMs] when
     * the engine runs at the idle emulator's 1 - 2 fps (or has not drawn a frame yet).
     */
    private suspend fun pace(slowMs: Long) {
        val fast = lastFrameDtMs in 1..FAST_FRAME_MS
        delay(if (fast) 16L else slowMs)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        stopSensing()
        panelShown.clear()
        runCatching { rig?.release() }
        figure?.destroy()
        pedestal?.destroy()
        still?.destroy()
        ring?.destroy()
        ghostRing?.destroy()
        timerRing?.destroy()
        orb?.destroy()
        disc?.destroy()
        runCatching { light?.destroy(true) }
        runCatching { fill?.destroy(true) }
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        meshes = null
        colliders = null
        rig = null
        figure = null
        pedestal = null
        still = null
        ring = null
        ghostRing = null
        timerRing = null
        orb = null
        disc = null
        light = null
        fill = null
        drag = null
        dialogPlaced = false
        anchorYaw = Float.NaN
        FmLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities (on state change)
    // =========================================================================================

    fun render(state: FmUiState) {
        val previous = ui
        ui = state
        if (!attached) return
        val now = SystemClock.elapsedRealtime()
        colliders?.active = state.relocating
        if (state.placeRequest == null) placeSent = false
        if (state.poseSerial != poseSerialSeen) {
            val first = poseSerialSeen < 0
            poseSerialSeen = state.poseSerial
            val target = Poses.resolved[state.poseIndex]
            val prop = Poses.all[state.poseIndex].prop
            if (first) {
                poseNow = target.copyOf()
                poseFrom = target.copyOf()
                poseTo = target.copyOf()
                poseStartMs = NEVER
                stoolFrom = if (prop == "stool") 1f else 0f
                stoolTo = stoolFrom
                figure?.apply(Mannequin.solve(poseNow))
            } else {
                poseFrom = poseNow.copyOf()
                poseTo = target.copyOf()
                poseStartMs = now
                stoolFrom = stoolTo
                stoolTo = if (prop == "stool") 1f else 0f
            }
        }
        if (state.content != contentShown) {
            if (contentShown != null) contentFadeStartMs = now
            contentShown = state.content
        }
        val p = state.placement
        if (p != null && p.serial != placementSeen) {
            val firstPlace = placementSeen < 0 || shownPos == null
            placementSeen = p.serial
            if (firstPlace) {
                shownPos = state.stand?.position
                shownYaw = state.stand?.yaw ?: 0f
                figureFadeStartMs = now
            }
        }
        if (state.ringFlashSerial != ringFlashSeen) {
            ringFlashSeen = state.ringFlashSerial
            ringFlashUntil = now + FLASH_MS
        }
        if (state.arrowFlashSerial != arrowFlashSeen) {
            arrowFlashSeen = state.arrowFlashSerial
            arrowFlashUntil = now + FLASH_MS
        }
        if (state.stand == null) shownPos = null
        if (previous.lightLux != state.lightLux || previous.fillLux != state.fillLux) applyLux(state)
        if (!state.relocating) ghostRing?.hide()
        syncPanels(state)
    }

    private fun applyLux(state: FmUiState) {
        light?.components?.get(DirectionalLightComponent::class.java)?.intensity = state.lightLux
        val f = fill ?: return
        val existing = f.components[DirectionalLightComponent::class.java]
        if (state.fillLux <= 0f) {
            if (existing != null) f.components.remove(DirectionalLightComponent::class.java)
        } else if (existing == null) {
            f.components.set(DirectionalLightComponent(FmColors.LampLight.color4(), state.fillLux, false))
        } else {
            existing.intensity = state.fillLux
        }
        FmLog.i("LIGHT lux main=${state.lightLux} fill=${state.fillLux}")
    }

    private fun syncPanels(state: FmUiState) {
        val rig = rig ?: return
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.phase == Phase.BLOCKED) {
            if (!dialogPlaced && hasHead) {
                val f = flat(head.forward)
                val down = Math.tan(Math.toRadians(5.0)).toFloat() * PanelSpec.DIALOG_DISTANCE_M
                dialog?.show(head.position + f * PanelSpec.DIALOG_DISTANCE_M - Vec3(0f, down, 0f), PanelSpec.SCALE)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        if (lastFrameMs != 0L) lastFrameDtMs = now - lastFrameMs
        lastFrameMs = now
        readHead(now)
        val rig = rig ?: return
        rig.onFrame(now)
        if (hasHead) rig.stowBehind(head.view)
        placePanels(rig, now)
        if (!attached) return
        val state = ui
        if (state.phase == Phase.BLOCKED && !dialogPlaced) syncPanels(state)
        pollBlock(now)
        pollFloor()
        autoPlace(state)
        figureFrame(state, now, dt)
        stickFrame(state, now, dt)
        faceButton(state)
        lightFrame(state)
        discFrame(state, now, dt)
        ringsFrame(state, now)
        ghostFrame(state)
        if (now - lastTickMs >= TICK_MS) {
            lastTickMs = now
            sink(FmEvent.Tick(now))
        }
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(FmEvent.HeadSample(head.position, head.forward))
        }
        if (hasHead) rig.faceViewer(head.position)
    }

    /**
     * Head pose; the first samples of a fresh tracker are the origin, and samples nearer than 0.3 m to the
     * floor are not a head either (Spatial Ruler): both are dropped.
     */
    private fun readHead(now: Long) {
        val p = headTracker.pose() ?: return
        if (p.position.length < 1e-4f) return
        if (!lastFloorY.isNaN() && p.position.y - lastFloorY < 0.3f) return
        head = p
        if (!hasHead) {
            hasHead = true
            firstHeadMs = now
        }
        // eye height: median of the first 3 s, then a slow low-pass every 10 s
        if (eyeHeight.isNaN()) {
            eyeSamples += p.position.y
            if (now - firstHeadMs >= EYE_SAMPLE_MS && eyeSamples.isNotEmpty()) {
                eyeHeight = eyeSamples.sorted()[eyeSamples.size / 2]
                eyeSamples.clear()
                lastEyeAdjustMs = now
                FmLog.i("PANEL eye_height=${FmLog.f(eyeHeight, 3)}")
            }
        } else if (now - lastEyeAdjustMs >= EYE_ADJUST_MS) {
            lastEyeAdjustMs = now
            eyeHeight += (p.position.y - eyeHeight) * 0.5f
        }
    }

    private fun pollBlock(now: Long) {
        val reason: BlockReason? = headTracker.blockReason()
        if (reason == null || ui.phase == Phase.BLOCKED) {
            blockSinceMs = NEVER
            if (ui.phase != Phase.BLOCKED) blockSent = false
            return
        }
        if (blockSinceMs == NEVER) blockSinceMs = now
        if (!blockSent && now - blockSinceMs >= BLOCK_AFTER_MS) {
            blockSent = true
            FmLog.i("BLOCKED reason=$reason")
            sink(FmEvent.BlockChanged(reason))
        }
    }

    private fun pollFloor() {
        val planes = planeTracker.all
        val y = PlaceRule.floorY(planes)
        val has = y != null
        if (has == lastFloor && (y == null || abs(y - lastFloorY) < 0.005f)) return
        lastFloor = has
        if (y != null) {
            lastFloorY = y
            colliders?.setFloor(y)
        }
        FmLog.i("PLANES n=${planes.size} floor=$has floorY=${y?.let { FmLog.f(it, 3) }} table=${planes.count { it.kind == SurfaceKind.TABLE }}")
        sink(FmEvent.FloorChanged(has, y ?: 0f))
    }

    /** The scene places the ring when the ViewModel asks and it has a floor and a head pose (design I1). */
    private fun autoPlace(state: FmUiState) {
        val reason = state.placeRequest ?: return
        if (placeSent || !hasHead || SystemClock.elapsedRealtime() - firstHeadMs < HEAD_SETTLE_MS) return
        val floor = PlaceRule.floor(planeTracker.all) ?: return
        val ahead = if (state.deskTier) PlaceRule.DESK_AHEAD_M else PlaceRule.FULL_AHEAD_M
        val (at, yaw) = PlaceRule.autoPlace(head.position, head.forward, floor, ahead)
        FmLog.i("PLACE auto reason=$reason at=${FmLog.pos(at)} head=${FmLog.pos(head.position)}")
        placeSent = true
        sink(FmEvent.Placed(at, yaw, onTable = false, front0 = frontOf(yaw), reason = reason))
    }

    /** Pose easing (0.6 s ease-in-out), position / yaw smoothing, fades, content and size. */
    private fun figureFrame(state: FmUiState, now: Long, dt: Float) {
        val fig = figure ?: return
        val st = state.stand
        val p = state.placement
        if (!state.placed || st == null || p == null) {
            fig.setShown(false)
            pedestal?.setOpacity(0f)
            still?.show(-1, Vec3.ZERO, 0f, 0f)
            return
        }
        // position and yaw: ~0.4 s to settle (relocation glides, drags and the stick follow closely)
        val target = st.position
        val cur = shownPos ?: target
        val k = 1f - exp(-dt / SMOOTH_S)
        shownPos = if (cur.distanceTo(target) > 4f) target else cur.lerp(target, k)
        shownYaw += angleDiff(st.yaw, shownYaw) * k
        val pos = shownPos!!

        // pose transition
        if (poseStartMs != NEVER) {
            var q = ((now - poseStartMs) / FmViewModel.TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
            if (state.frozen) q = q.coerceAtMost(0.5f)
            val e = Ease.inOutCubic(q)
            poseNow = J.lerp(poseFrom, poseTo, e)
            fig.apply(Mannequin.solve(poseNow))
            if (q >= 1f) poseStartMs = NEVER
        } else if (fig.frame == null) {
            fig.apply(Mannequin.solve(poseNow))
        }
        val stoolT = if (poseStartMs == NEVER) 1f else ((now - poseStartMs) / STOOL_FADE_MS).coerceIn(0f, 1f)
        val stool = stoolFrom + (stoolTo - stoolFrom) * stoolT

        // content cross-fade (0.4 s) and the first fade-in from 30 %
        val fade = if (contentFadeStartMs == NEVER) 1f else ((now - contentFadeStartMs) / CONTENT_FADE_MS).coerceIn(0f, 1f)
        if (fade >= 1f) contentFadeStartMs = NEVER
        val placeFade = if (figureFadeStartMs == NEVER) 1f else (0.3f + 0.7f * ((now - figureFadeStartMs) / PLACE_FADE_MS)).coerceIn(0.3f, 1f)
        if (placeFade >= 1f) figureFadeStartMs = NEVER
        val onPedestal = state.deskTier && !p.onTable
        val top = if (onPedestal) pos + Vec3(0f, PedestalView.HEIGHT_M, 0f) else pos
        pedestal?.let {
            it.place(pos, shownYaw)
            it.setOpacity(if (onPedestal) placeFade else 0f)
        }
        val figureOn = state.content == Content.FIGURE
        if (figureOn) {
            fig.setShown(true)
            val scale = if (state.size == FigureSize.DESK) DESK_SCALE else 1f
            fig.place(top, shownYaw, scale)
            fig.setOpacity(placeFade * fade)
            fig.setStool(stool * placeFade * fade, fig.frame?.pelvisBottom ?: 0.44f)
            still?.show(-1, Vec3.ZERO, 0f, 0f)
        } else {
            fig.setShown(false)
            // sets swap half way through the 0.6 s transition: fade out, swap, fade in
            val q = if (poseStartMs == NEVER) 1f else ((now - poseStartMs) / FmViewModel.TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
            if (q >= 0.5f || stillShown < 0) stillShown = state.stillIndex
            val stillFade = abs(1f - 2f * q).coerceAtLeast(0.05f)
            still?.show(stillShown, top + Vec3(0f, 0.001f, 0f), shownYaw, placeFade * fade * (if (poseStartMs == NEVER) 1f else stillFade))
        }
    }

    /** Thumbstick X turns the figure about its vertical axis (factory rule D1, design `rules.rotate`). */
    private fun stickFrame(state: FmUiState, now: Long, dt: Float) {
        val sample = if (state.placed && drag == null && !state.relocating && !state.menuOpen) controller.sample() else null
        val x = state.debugStick ?: sample?.stickX ?: 0f
        val held = abs(x) > STICK_DEAD
        val wanted = if (held) -(if (x > 0f) 1f else -1f) * (abs(x) - STICK_DEAD) / (1f - STICK_DEAD) * STICK_RATE_RAD_S else 0f
        if (held) {
            if (!stickHeld) FmLog.i("STICK start x=${FmLog.f(x)}")
            stickHeld = true
            stickRate = wanted
        } else if (stickRate != 0f) {
            // release: linear stop over 0.2 s
            val step = STICK_RATE_RAD_S / STICK_STOP_S * dt
            stickRate = if (abs(stickRate) <= step) 0f else stickRate - step * (if (stickRate > 0f) 1f else -1f)
        }
        if (stickRate != 0f && dt > 0f) sink(FmEvent.StickTurn(stickRate * dt))
        if (!held && stickRate == 0f && stickHeld) {
            stickHeld = false
            FmLog.i("STICK release")
            sink(FmEvent.StickReleased)
        }
    }

    /** A / X: next pose (design I6). */
    private fun faceButton(state: FmUiState) {
        val n = controller.takeFacePresses()
        if (n > 0 && state.placed && !state.menuOpen && !state.relocating) {
            FmLog.i("BUTTON next")
            sink(FmEvent.NextClicked("button"))
        }
    }

    /** Orbit centre of the light: chest of the life-size figure, 0.2 m above the top for the desk tier. */
    private fun lightCenter(state: FmUiState): Vec3? {
        val pos = shownPos ?: return null
        val p = state.placement ?: return null
        return if (!state.deskTier) Vec3(pos.x, pos.y + CHEST_M, pos.z)
        else Vec3(pos.x, pos.y + (if (p.onTable) 0f else PedestalView.HEIGHT_M) + 0.2f, pos.z)
    }

    private fun lightFrame(state: FmUiState) {
        val p = state.placement
        val center = lightCenter(state)
        val o = orb ?: return
        if (p == null || center == null || !state.placed) {
            o.hide()
            return
        }
        val dir = LightRule.direction(p.front0, state.settings.lightAzDeg, state.settings.lightElDeg)
        light?.setLocal(position = center + dir * 2f, rotation = Quat4.fromTo(Vec3.AHEAD, -dir))
        // optional fill (debug `lux`): low from the opposite side, so the shadow side is not black
        val fillDir = Vec3(-dir.x, 0.3f, -dir.z).normalized()
        fill?.setLocal(rotation = Quat4.fromTo(Vec3.AHEAD, -fillDir))
        if (!state.settings.orbShown && drag != DragKind.ORB) {
            o.hide()
            return
        }
        val nominal = if (state.deskTier) LightRule.ORBIT_DESK_M else LightRule.ORBIT_FULL_M
        val at = LightRule.orbPosition(center, dir, nominal, lastFloorY.takeIf { !it.isNaN() } ?: p.ring.y, if (hasHead) head.position else null)
        o.show(at, center, big = drag == DragKind.ORB)
    }

    /**
     * The next disc (design `rules.dwell`): beside the figure on the viewer's right at waist height, pushed out
     * when a limb comes near, on the left when it would stand at a wall; the head's view line held on it for
     * 1.5 s (or a tap) moves on. The rim fills from 0.3 s to 1.5 s.
     */
    private fun discFrame(state: FmUiState, now: Long, dt: Float) {
        val d = disc ?: return
        val pos = shownPos
        val p = state.placement
        if (!state.discShown || pos == null || p == null || !hasHead) {
            d.hide()
            clearDwell(state)
            return
        }
        val desk = state.deskTier
        val baseY = if (!desk) pos.y + DISC_HEIGHT_M else pos.y + (if (p.onTable) 0f else PedestalView.HEIGHT_M) + 0.15f
        val toHead = flat(head.position - pos)
        val viewerRight = (-toHead).cross(Vec3.UP).normalized()
        // limbs of the current pose in the room
        val near = if (desk) 0.05f else 0.10f
        val normal = if (desk) 0.35f else DISC_OFFSET_M
        val out = if (desk) 0.45f else DISC_OUT_M
        val side = if (discLeft) -1f else 1f
        val probe = Vec3(pos.x, baseY, pos.z) + viewerRight * (side * discOffset)
        val limbNear = !desk && limbDistance(probe, pos) < near
        if (limbNear) discOutUntil = now + DISC_BACK_MS
        val wanted = if (now < discOutUntil) out else normal
        discOffset = wanted
        // a wall on the right (floor edge within 0.2 m) moves the disc to the left
        val floor = PlaceRule.floor(planeTracker.all)
        val rightPoint = Vec3(pos.x, pos.y, pos.z) + viewerRight * wanted
        val inset = PlaceRule.insetOnFloor(floor, rightPoint)
        discLeft = inset != null && inset < 0.2f
        val at = Vec3(pos.x, baseY, pos.z) + viewerRight * ((if (discLeft) -1f else 1f) * wanted)
        // dwell: the head's forward ray within 0.09 m of the disc centre
        val toDisc = at - head.position
        val along = toDisc.dot(head.forward)
        val miss = if (along <= 0f) Float.MAX_VALUE else (toDisc - head.forward * along).length
        val onIt = miss <= DWELL_RADIUS_M && now >= dwellCoolUntil
        var held = 0f
        val debug = state.debugDwell
        if (debug != null) {
            held = debug
        } else if (onIt) {
            if (dwellStartMs == NEVER) dwellStartMs = now
            held = (now - dwellStartMs) / 1000f
        } else {
            dwellStartMs = NEVER
        }
        val progress = ((held - DWELL_SHOW_S) / (DWELL_S - DWELL_SHOW_S)).coerceIn(0f, 1f)
        val active = held >= DWELL_SHOW_S
        if (active != dwellActiveSent) {
            dwellActiveSent = active
            sink(FmEvent.DwellChanged(active))
        }
        if (debug == null && held >= DWELL_S) {
            FmLog.i("DWELL done")
            dwellStartMs = NEVER
            dwellCoolUntil = now + DWELL_COOL_MS
            dwellActiveSent = false
            sink(FmEvent.DwellChanged(false))
            sink(FmEvent.NextClicked("dwell"))
        }
        d.show(at, (head.position - at).normalized(), viewerRight, progress, 1f)
    }

    private fun clearDwell(state: FmUiState) {
        dwellStartMs = NEVER
        if (dwellActiveSent || (state.dwelling && state.debugDwell == null)) {
            dwellActiveSent = false
            sink(FmEvent.DwellChanged(false))
        }
    }

    /** Smallest distance from [p] to a limb point of the shown pose (life-size figure). */
    private fun limbDistance(p: Vec3, pos: Vec3): Float {
        val f = figure?.frame ?: return Float.MAX_VALUE
        val turn = Quat4.axisAngle(Vec3.UP, shownYaw)
        var best = Float.MAX_VALUE
        for (l in f.limbs) best = min(best, (pos + turn.rotate(l)).distanceTo(p))
        return best
    }

    /** Stand ring (25 % with the figure on it, 100 % otherwise, green flash) and the timer ring. */
    private fun ringsFrame(state: FmUiState, now: Long) {
        val r = ring ?: return
        val tr = timerRing ?: return
        val m = materials ?: return
        val p = state.placement
        val pos = shownPos
        if (!state.placed || p == null || pos == null) {
            r.hide()
            tr.hide()
            return
        }
        val small = p.onTable
        r.place(p.ring, p.ringYaw, small)
        val st = state.stand
        val onRing = st != null && Vec3(st.position.x - p.ring.x, 0f, st.position.z - p.ring.z).length <= FmViewModel.ON_RING_M
        val bright = state.dragging || !onRing || now - state.placedAtMs < RING_BRIGHT_MS || state.relocating
        val ringMat = if (now < ringFlashUntil) m.ringOk else if (bright) m.ring else m.ringDim
        val arrowMat = if (now < arrowFlashUntil) m.ringOk else ringMat
        r.look(ringMat, arrowMat)
        // timer ring
        if (state.duration.seconds == null) {
            tr.hide()
            return
        }
        val share =
            when {
                state.phase == Phase.DRAWING && state.endsAtMs != null && !state.frozen && now >= state.transitionUntilMs ->
                    ((state.endsAtMs - now).toFloat() / state.durationMs).coerceIn(0f, 1f)
                state.phase == Phase.DRAWING && now < state.transitionUntilMs -> 1f
                else -> (state.remainingMs.toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        val alertArc = state.lastFive
        val breathe = if (state.phase == Phase.PAUSED) 0.55f + 0.45f * ((1 + kotlin.math.cos(2 * Math.PI * (now % 1200L) / 1200.0)).toFloat() / 2f) else 1f
        tr.show(pos, shownYaw, if (small) TIMER_SMALL_SCALE else 1f, share, alertArc, breathe)
    }

    /** S18 ghost ring under the controller ray (ray channel only; eye + pinch has no hover point). */
    private fun ghostFrame(state: FmUiState) {
        val g = ghostRing ?: return
        val m = materials ?: return
        if (!state.relocating) {
            g.hide()
            return
        }
        val ray = controller.sample()?.ray
        if (ray == null) {
            g.hide()
            return
        }
        val planes = planeTracker.all.filter { it.kind == SurfaceKind.FLOOR || (it.kind == SurfaceKind.TABLE && it.horizontal) }
        val hit = hits.nearest(planes, ray.origin, ray.direction, 8f)
        if (hit == null) {
            g.hide()
            return
        }
        val table = hit.plane.kind == SurfaceKind.TABLE
        val bad = table && !state.deskTier
        g.place(hit.point, yawFacing(flat(head.position - hit.point)), smallSize = table)
        g.look(if (bad) m.ghostBad else m.ghost)
    }

    // =========================================================================================
    // panels: yaw-only lazy follow with PanelDodge (design `rules.panelFollow`)
    // =========================================================================================

    private fun headYaw(): Float {
        val f = flat(head.forward)
        return atan2(-f.x, -f.z)
    }

    private fun dirOfYaw(yaw: Float): Vec3 = Vec3(-kotlin.math.sin(yaw), 0f, -kotlin.math.cos(yaw))

    private fun placePanels(rig: PanelRig, now: Long) {
        if (!hasHead) return
        val state = ui
        // until the eye height is measured (3 s), the current head height stands in, so S1 shows the panel
        val eye = if (eyeHeight.isNaN()) head.position.y else eyeHeight
        val yawNow = headYaw()
        val pitchDeg = state.debugPitchDeg ?: Math.toDegrees(asin(head.forward.y.coerceIn(-1f, 1f)).toDouble()).toFloat()
        if (anchorYaw.isNaN()) {
            anchorYaw = yawNow
            anchorHead = head.position
        }
        val hold = state.menuOpen || pitchDeg < LOOK_DOWN_DEG || drag != null
        if (slideStartMs != NEVER) {
            val q = ((now - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = Ease.inOutCubic(q)
            anchorYaw = slideFromYaw + angleDiff(yawNow, slideFromYaw) * e
            anchorHead = slideFromHead.lerp(head.position, e)
            if (q >= 1f) slideStartMs = NEVER
        } else {
            val off = abs(angleDiff(yawNow, anchorYaw))
            val moved = Vec3(head.position.x - anchorHead.x, 0f, head.position.z - anchorHead.z).length
            val outside = off > rad(FOLLOW_WINDOW_DEG) || moved > FOLLOW_MOVE_M
            if (!outside || hold) {
                outSinceMs = NEVER
            } else if (outSinceMs == NEVER) {
                outSinceMs = now
            } else if (now - outSinceMs >= FOLLOW_OUT_MS) {
                slideFromYaw = anchorYaw
                slideFromHead = anchorHead
                slideStartMs = now
                outSinceMs = NEVER
                FmLog.i("FOLLOW panel slide off=${FmLog.f(Math.toDegrees(off.toDouble()).toFloat(), 1)} moved=${FmLog.f(moved)}")
            }
        }
        // PanelDodge: the figure's bounds seen from the head overlap the panel -> push left / up
        updateDodge(state, now)
        val panelYaw = anchorYaw + rad(PANEL_LEFT_DEG) + dodgeYaw
        val dir = dirOfYaw(panelYaw)
        val base = Vec3(anchorHead.x, eye + PANEL_ABOVE_EYE_M, anchorHead.z)
        val lift = Math.tan(dodgePitch.toDouble()).toFloat() * PANEL_DISTANCE_M
        val at = base + dir * PANEL_DISTANCE_M + Vec3(0f, lift, 0f)
        val rot = Quat4.axisAngle(Vec3.UP, panelYaw)
        rig[PanelSpec.CONTROL]?.place(at, rot, PanelSpec.SCALE)
        if (state.menuOpen) {
            val (cw, ch) = PanelSpec.halfM(PanelSpec.CONTROL)
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU)
            val gap = PanelSpec.MENU_GAP_DP * PanelRig.DP_M * PanelSpec.SCALE
            rig[PanelSpec.MENU]?.place(at + rot.rotate(Vec3(cw + gap + mw, ch - mh, 0f)), rot, PanelSpec.SCALE)
        }
    }

    private fun updateDodge(state: FmUiState, now: Long) {
        val f = figure?.frame
        val pos = shownPos
        if (f == null || pos == null || !state.placed || state.content != Content.FIGURE || state.size != FigureSize.FULL) {
            relaxDodge(now)
            return
        }
        // angular box of the figure (bounds corners) relative to the head
        val turn = Quat4.axisAngle(Vec3.UP, shownYaw)
        var minYaw = Float.MAX_VALUE
        var maxYaw = -Float.MAX_VALUE
        var maxPitch = -Float.MAX_VALUE
        val hy = headYaw()
        for (x in listOf(f.min.x, f.max.x)) for (y in listOf(f.min.y, f.max.y)) for (z in listOf(f.min.z, f.max.z)) {
            val w = pos + turn.rotate(Vec3(x, y, z)) - head.position
            val horiz = sqrt(w.x * w.x + w.z * w.z)
            if (horiz < 0.1f) continue
            val yaw = angleDiff(atan2(-w.x, -w.z), hy)
            val pitch = atan2(w.y, horiz)
            minYaw = min(minYaw, yaw)
            maxYaw = max(maxYaw, yaw)
            maxPitch = max(maxPitch, pitch)
        }
        if (minYaw == Float.MAX_VALUE) {
            relaxDodge(now)
            return
        }
        // panel box: centre left 30 degrees (+ yaw = left), about +/- 10 degrees wide and 7 high around eye + 6 degrees
        val anchorRel = angleDiff(anchorYaw, hy)
        val panelCenter = anchorRel + rad(PANEL_LEFT_DEG)
        val halfW = rad(10.5f)
        val panelBottom = rad(PANEL_PITCH_DEG - 7f)
        val overlapYaw = maxYaw - (panelCenter - halfW)
        val overlapsVert = maxPitch > panelBottom
        if (overlapYaw > 0f && minYaw < panelCenter + halfW && overlapsVert) {
            // up first when that clears it (the panel stays in view), else left, then up (emulator finding)
            val up = maxPitch - panelBottom + rad(2f)
            if (up <= rad(DODGE_MAX_UP_DEG)) {
                dodgeYaw = 0f
                dodgePitch = up
            } else {
                dodgeYaw = min(rad(DODGE_MAX_LEFT_DEG), overlapYaw + rad(2f))
                val stillOver = maxYaw > panelCenter + dodgeYaw - halfW
                dodgePitch = if (stillOver) min(rad(DODGE_MAX_UP_DEG), up) else 0f
            }
            dodgeClearSince = NEVER
        } else {
            relaxDodge(now)
        }
    }

    private fun relaxDodge(now: Long) {
        if (dodgeYaw == 0f && dodgePitch == 0f) return
        if (dodgeClearSince == NEVER) dodgeClearSince = now
        if (now - dodgeClearSince >= DODGE_BACK_MS) {
            dodgeYaw *= 0.9f
            dodgePitch *= 0.9f
            if (abs(dodgeYaw) < 0.002f) dodgeYaw = 0f
            if (abs(dodgePitch) < 0.002f) dodgePitch = 0f
        }
    }

    // =========================================================================================
    // system pointer (gaze + pinch, controller trigger, emulator mouse)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        val state = ui
        if (target == null) {
            FmLog.i("TAP other target=none")
            return
        }
        if (state.menuOpen) {
            FmLog.i("TAP outside menu target=$name")
            sink(FmEvent.TappedOutside)
            return
        }
        val c = colliders
        when {
            state.relocating && c != null && (c.isFloor(target) || c.planeIdOf(target) != null) && stage != null -> {
                val table = c.planeIdOf(target) != null
                val y = if (table) stage.y else lastFloorY.takeIf { !it.isNaN() } ?: stage.y
                FmLog.i("TAP relocate ${if (table) "table" else "floor"} stage=${FmLog.pos(stage)}")
                sink(FmEvent.RelocateTap(Vec3(stage.x, y, stage.z), table))
            }
            disc?.isGrab(target) == true -> {
                FmLog.i("TAP disc")
                sink(FmEvent.NextClicked("disc"))
            }
            else -> FmLog.i("TAP other target=$name stage=${FmLog.pos(stage)}")
        }
    }

    /**
     * Drags: the figure (or pedestal) moves on the floor by the hand's travel (`dragAmount`, view pixels,
     * 0.4 mm each at 1 m times the depth factor) along the head's horizontal right / forward; the orb moves
     * across the view and the light direction follows it. Only movement counts, never how long it is held.
     */
    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val state = ui
        when (phase) {
            "start" -> {
                val target = value.targetEntity ?: return
                if (state.menuOpen || state.relocating || !state.placed) return
                val pos = shownPos ?: return
                when {
                    orb?.isGrab(target) == true -> {
                        val center = lightCenter(state) ?: return
                        val p = state.placement ?: return
                        val dir = LightRule.direction(p.front0, state.settings.lightAzDeg, state.settings.lightElDeg)
                        val at = LightRule.orbPosition(center, dir, if (state.deskTier) LightRule.ORBIT_DESK_M else LightRule.ORBIT_FULL_M, lastFloorY.takeIf { !it.isNaN() } ?: p.ring.y, head.position)
                        orbDragPos = at
                        dragPxToM = PX_M * (0.67f + 1.186f * head.position.distanceTo(at).coerceAtLeast(0.3f))
                        drag = DragKind.ORB
                        FmLog.i("DRAG orb start")
                        sink(FmEvent.LightDragStarted)
                    }
                    (figure?.isGrab(target) == true || pedestal?.isGrab(target) == true) && !stickHeld -> {
                        dragPxToM = PX_M * (0.67f + 1.186f * head.position.distanceTo(pos + Vec3(0f, 1f, 0f)).coerceAtLeast(0.3f))
                        drag = DragKind.FIGURE
                        FmLog.i("DRAG figure start")
                        sink(FmEvent.DragStarted)
                    }
                }
            }
            "move" -> {
                val kind = drag ?: return
                val amount = value.dragAmount
                val right = flat(head.right, Vec3.RIGHT)
                when (kind) {
                    DragKind.FIGURE -> {
                        val fwd = flat(head.forward)
                        val delta = right * (amount.x * dragPxToM) - fwd * (amount.y * dragPxToM)
                        if (delta.length > 1e-5f) sink(FmEvent.DragMoved(delta))
                    }
                    DragKind.ORB -> {
                        val p = state.placement ?: return
                        val center = lightCenter(state) ?: return
                        val from = orbDragPos ?: return
                        val moved = from + right * (amount.x * dragPxToM) - Vec3.UP * (amount.y * dragPxToM)
                        orbDragPos = moved
                        val dir = (moved - center).normalized()
                        val el = Math.toDegrees(asin(dir.y.coerceIn(-1f, 1f)).toDouble()).toFloat()
                        sink(FmEvent.LightSet(LightRule.azimuthOf(p.front0, dir), el.coerceIn(LightRule.EL_MIN, LightRule.EL_MAX)))
                    }
                }
            }
            "end", "cancel" -> {
                val kind = drag ?: return
                drag = null
                orbDragPos = null
                FmLog.i("DRAG ${kind.name.lowercase(Locale.US)} end")
                sink(if (kind == DragKind.ORB) FmEvent.LightDragEnded else FmEvent.DragEnded)
            }
        }
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { FmLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${FmLog.pos5(head.position)} ${FmLog.quat5(head.rotation)} fwd=${FmLog.pos5(head.forward)} valid=$hasHead eye=${FmLog.f(eyeHeight, 3)} floorY=${FmLog.f(lastFloorY, 3)}"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f dodge=%.1f/%.1f", slot.id, FmLog.pos5(slot.position), FmLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, Math.toDegrees(dodgeYaw.toDouble()), Math.toDegrees(dodgePitch.toDouble()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${FmLog.pos(p.centroid)} normal=${FmLog.pos(p.normal)} verts=${p.vertices.size} top=${FmLog.f(p.maxY)}" }

    override fun parkPanel(): String {
        if (hasHead) {
            slideFromYaw = anchorYaw
            slideFromHead = anchorHead
            slideStartMs = SystemClock.elapsedRealtime()
        }
        return "FOLLOW panel park requested"
    }

    override fun figureLine(): String {
        val f = figure?.frame ?: return "FIGURE none"
        return "FIGURE pos=${FmLog.pos(shownPos)} yaw=${FmLog.f(Math.toDegrees(shownYaw.toDouble()).toFloat(), 1)} head_top=${FmLog.f(f.headTop, 3)} min=${FmLog.pos(f.min)} max=${FmLog.pos(f.max)} chest=${FmLog.pos(f.chest)}"
    }

    /** Debug `place=x,z[,table]`: the ring straight to Stage coordinates, as if tapped during S18. */
    override fun placeAt(x: Float, z: Float, table: Boolean): String {
        val planes = planeTracker.all
        val y =
            if (table) {
                planes.filter { it.kind == SurfaceKind.TABLE && it.horizontal }.firstOrNull { hits.contains(it, Vec3(x, it.maxY, z), 0.05f) || hits.contains(it, Vec3(x, it.minY, z), 0.05f) }?.let { (it.minY + it.maxY) / 2f }
            } else {
                lastFloorY.takeIf { !it.isNaN() }
            } ?: return "PLACE failed: no ${if (table) "table" else "floor"} at ($x, $z)"
        val wasRelocating = ui.relocating
        if (!wasRelocating) sink(FmEvent.RelocateClicked)
        sink(FmEvent.RelocateTap(Vec3(x, y, z), table))
        return "PLACE ring at (${FmLog.f(x)}, ${FmLog.f(y)}, ${FmLog.f(z)}) table=$table"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val TICK_MS = 100L
        private const val HEAD_SAMPLE_MS = 100L
        private const val BLOCK_AFTER_MS = 2_000L
        private const val HEAD_SETTLE_MS = 500L
        private const val FAST_FRAME_MS = 40L
        private const val MESH_GAP_SLOW_MS = 2_500L
        private const val ENTITY_GAP_SLOW_MS = 800L
        private const val FLASH_MS = 300L
        private const val RING_BRIGHT_MS = 2_000L
        private const val SMOOTH_S = 0.08f
        private const val STOOL_FADE_MS = 300f
        private const val CONTENT_FADE_MS = 400f
        private const val PLACE_FADE_MS = 400f
        private const val DESK_SCALE = 0.2f
        private const val CHEST_M = 1.30f
        private const val TIMER_SMALL_SCALE = 0.11f / 0.38f

        private const val STICK_DEAD = 0.15f
        private const val STICK_RATE_RAD_S = 1.0472f
        private const val STICK_STOP_S = 0.2f

        private const val DISC_HEIGHT_M = 1.05f
        private const val DISC_OFFSET_M = 0.55f
        private const val DISC_OUT_M = 0.70f
        private const val DISC_BACK_MS = 500L
        private const val DWELL_RADIUS_M = 0.09f
        private const val DWELL_SHOW_S = 0.3f
        private const val DWELL_S = 1.5f
        private const val DWELL_COOL_MS = 800L

        private const val EYE_SAMPLE_MS = 3_000L
        private const val EYE_ADJUST_MS = 10_000L
        private const val PANEL_LEFT_DEG = 30f
        private const val PANEL_ABOVE_EYE_M = 0.07f
        private const val PANEL_PITCH_DEG = 6f
        private const val PANEL_DISTANCE_M = 0.65f
        private const val FOLLOW_WINDOW_DEG = 45f
        private const val FOLLOW_MOVE_M = 0.5f
        private const val FOLLOW_OUT_MS = 500L
        private const val SLIDE_MS = 400f
        private const val LOOK_DOWN_DEG = -20f
        private const val DODGE_MAX_LEFT_DEG = 15f
        private const val DODGE_MAX_UP_DEG = 12f
        private const val DODGE_BACK_MS = 1_000L

        /** View pixels of a system drag -> metres at 1 m (0.4 mm each, times the depth factor). */
        private const val PX_M = 0.0004f

        private fun rad(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()
    }
}
