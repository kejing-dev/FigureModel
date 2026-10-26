package io.embodit.figuremodel.ui.fm.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.GroundShadowComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import io.embodit.figuremodel.domain.model.FigureFrame
import io.embodit.figuremodel.domain.model.Part
import io.embodit.figuremodel.domain.model.Quat4
import io.embodit.figuremodel.domain.model.Solid
import io.embodit.figuremodel.domain.model.StillLife
import io.embodit.figuremodel.domain.model.Vec3
import kotlin.math.PI

/**
 * Shared meshes of the scene: eleven, built one at a time by [build] with [pace] in between. Each mesh costs the
 * main thread about a second on the idle emulator; all at once is a start-up "isn't responding" (factory finding).
 */
internal class FmMeshes {
    lateinit var sphere: MeshResource
    lateinit var neck: MeshResource
    lateinit var limb: MeshResource
    lateinit var cube: MeshResource
    lateinit var cylinder: MeshResource

    /** Stand ring: 36 dashes, 2 cm line, 0.9 m across; the small 0.3 m ring is the same mesh at a third. */
    lateinit var ring: MeshResource
    lateinit var arrowHead: MeshResource

    /** Timer ring r 0.38 m, 3 cm wide: the track and one 5 degree arc piece (72 make the circle). */
    lateinit var track: MeshResource
    lateinit var timerPiece: MeshResource

    /** Next disc 0.12 m across with its progress rim (36 pieces of 10 degrees). */
    lateinit var disc: MeshResource
    lateinit var discPiece: MeshResource

    fun limbFor(kind: Part.Kind): MeshResource = if (kind == Part.Kind.NECK) neck else limb

    suspend fun build(shared: SharedResources, pace: suspend () -> Unit) {
        sphere = shared.keep(MeshFactory.unitSphere("unitSphere"))
        pace()
        neck = shared.keep(MeshFactory.frustum(Part.Kind.NECK.taper, "frustumNeck"))
        pace()
        limb = shared.keep(MeshFactory.frustum(LIMB_TAPER, "frustumLimb"))
        pace()
        cube = shared.keep(MeshFactory.unitCube("unitCube"))
        pace()
        cylinder = shared.keep(MeshFactory.cylinder(1f, 1f, "unitCylinder", segments = 20))
        pace()
        ring = shared.keep(MeshFactory.ring(0.44f, 0.46f, "standRing", dashes = 36, dashFill = 0.55f))
        pace()
        arrowHead = shared.keep(MeshFactory.arrowHead("arrowHead"))
        pace()
        track = shared.keep(MeshFactory.ring(0.365f, 0.395f, "timerTrack", segments = 72))
        pace()
        timerPiece = shared.keep(MeshFactory.arcPiece(0.365f, 0.395f, (2 * PI / TIMER_PIECES).toFloat() * 1.02f, "timerPiece"))
        pace()
        disc = shared.keep(MeshFactory.disc(0.06f, "disc", segments = 36))
        pace()
        discPiece = shared.keep(MeshFactory.arcPiece(0.06f, 0.07f, (2 * PI / DISC_PIECES).toFloat() * 1.04f, "discPiece"))
    }

    companion object {
        const val TIMER_PIECES = 72
        const val DISC_PIECES = 36

        /** One taper for arms and legs (0.70 - 0.78 in the design): one mesh instead of four. */
        const val LIMB_TAPER = 0.74f
    }
}

private fun Entity.castShadow() {
    runCatching { components.set(GroundShadowComponent(true, false)) }
}

private fun model(mesh: MeshResource, material: Material, name: String): Entity = ModelEntity(mesh, material).named(name)

/**
 * The procedural wooden mannequin (design `entities3d.mannequin`): 30 parts as scaled shared meshes under one
 * root; a pose only writes transforms. The root carries the stand position, the yaw about the vertical
 * through the ankles and the scale (1 or 0.2 for the desk figure). The stool of the seated pose hangs here too.
 */
internal class FigureView(parent: Entity, m: SceneMaterials, meshes: FmMeshes) {
    val root: Entity = Entity().named("figure")
    private val parts = ArrayList<Entity>(Part.entries.size)
    val grab: Entity
    private val stool: Entity = Entity().named("stool")
    private val stoolTop: Entity
    private val stoolLegs = ArrayList<Entity>(4)
    var frame: FigureFrame? = null
        private set

    init {
        parent.addChild(root)
        for (p in Part.entries) {
            val mesh =
                when (p.kind) {
                    Part.Kind.ELLIPSOID, Part.Kind.BALL -> meshes.sphere
                    Part.Kind.HAND, Part.Kind.FOOT -> meshes.cube
                    else -> meshes.limbFor(p.kind)
                }
            val e = model(mesh, if (p.joint) m.woodDark else m.wood, "fig_${p.name.lowercase()}")
            e.castShadow()
            root.addChild(e)
            parts += e
        }
        // the drag target: a 0.5 x 1.75 m column (design `components.Collision`)
        grab = Entity().named("figure_grab")
        grab.components.set(
            CollisionComponent(
                collisionShape = listOf(ShapeResource.createBox(Vec3(0.5f, 1.75f, 0.5f).sdk()).offsetByTranslation(Vec3(0f, 0.875f, 0f).sdk())),
                physicsMaterial = PhysicsMaterialResource(),
            )
        )
        grab.components.set(InteractableComponent())
        runCatching { grab.components.set(HoverEffectComponent()) }
        root.addChild(grab)
        // stool 0.40 x 0.40, top board 4 cm, four legs Ø 4 cm; its height follows the pelvis
        root.addChild(stool)
        stoolTop = model(meshes.cube, m.wood, "stool_top").also {
            it.castShadow()
            stool.addChild(it)
        }
        for (i in 0 until 4) {
            val leg = model(meshes.cylinder, m.wood, "stool_leg$i")
            leg.castShadow()
            stool.addChild(leg)
            stoolLegs += leg
        }
        stool.enabled = false
    }

    fun apply(f: FigureFrame) {
        frame = f
        for ((i, e) in parts.withIndex()) {
            val p = f.parts[i]
            e.setLocal(position = p.position, rotation = p.rotation, scale = p.scale)
        }
    }

    fun place(position: Vec3, yaw: Float, scale: Float) {
        root.setLocal(position = position, rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(scale, scale, scale))
    }

    /** Stool under the pelvis at [opacity] (0 hides it); [top] = seat height in figure metres. */
    fun setStool(opacity: Float, top: Float) {
        if (opacity <= 0.01f) {
            if (stool.enabled) stool.enabled = false
            return
        }
        if (!stool.enabled) stool.enabled = true
        val h = top.coerceIn(0.2f, 0.7f)
        stoolTop.setLocal(position = Vec3(0f, h - 0.02f, 0f), scale = Vec3(0.40f, 0.04f, 0.40f))
        val legH = h - 0.04f
        val c = 0.17f
        val corners = listOf(Vec3(-c, 0f, -c), Vec3(c, 0f, -c), Vec3(c, 0f, c), Vec3(-c, 0f, c))
        for ((i, leg) in stoolLegs.withIndex()) leg.setLocal(position = corners[i], scale = Vec3(0.02f, legH, 0.02f))
        stool.setOpacity(opacity)
    }

    fun setOpacity(value: Float) = root.setOpacity(value)

    fun setShown(shown: Boolean) {
        if (root.enabled != shown) root.enabled = shown
    }

    fun isGrab(e: Entity): Boolean = e.id == grab.id || parts.any { it.id == e.id }

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** The pedestal 0.45 x 0.75 x 0.45 m with a slightly darker top (design `entities3d.pedestal`). */
internal class PedestalView(parent: Entity, m: SceneMaterials, meshes: FmMeshes) {
    val root: Entity = Entity().named("pedestal")
    val grab: Entity

    init {
        parent.addChild(root)
        val body = model(meshes.cube, m.wood, "pedestal_body")
        body.setLocal(position = Vec3(0f, HEIGHT_M / 2f, 0f), scale = Vec3(0.45f, HEIGHT_M, 0.45f))
        body.castShadow()
        root.addChild(body)
        val top = model(meshes.cube, m.woodDark, "pedestal_top")
        top.setLocal(position = Vec3(0f, HEIGHT_M - 0.004f, 0f), scale = Vec3(0.454f, 0.01f, 0.454f))
        root.addChild(top)
        grab = Entity().named("pedestal_grab")
        grab.tapBox(Vec3(0.45f, HEIGHT_M, 0.45f), Vec3(0f, HEIGHT_M / 2f, 0f))
        runCatching { grab.components.set(HoverEffectComponent()) }
        root.addChild(grab)
        root.enabled = false
    }

    fun place(position: Vec3, yaw: Float) = root.setLocal(position = position, rotation = Quat4.axisAngle(Vec3.UP, yaw))

    fun setOpacity(value: Float) {
        val on = value > 0.01f
        if (root.enabled != on) root.enabled = on
        if (on) root.setOpacity(value)
    }

    fun isGrab(e: Entity): Boolean = root.enabled && e.id == grab.id

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    companion object {
        const val HEIGHT_M = 0.75f
    }
}

/** The five plaster still-life sets (design `stillLifeSets`); a set's meshes are built the first time it shows. */
internal class StillLifeView(parent: Entity, private val shared: SharedResources, private val m: SceneMaterials, private val meshes: FmMeshes) {
    val root: Entity = Entity().named("stillLife")
    private val groups = arrayOfNulls<Entity>(StillLife.count)
    private var shownIndex = -1

    init {
        parent.addChild(root)
        root.enabled = false
    }

    /** Shows set [index] on a top surface at [top] turned by [yaw], at [opacity]; -1 hides all. */
    fun show(index: Int, top: Vec3, yaw: Float, opacity: Float) {
        if (index < 0 || opacity <= 0.01f) {
            if (root.enabled) root.enabled = false
            return
        }
        if (!root.enabled) root.enabled = true
        root.setLocal(position = top, rotation = Quat4.axisAngle(Vec3.UP, yaw))
        if (index != shownIndex) {
            groups.forEachIndexed { i, g -> g?.enabled = i == index }
            if (groups[index] == null) groups[index] = build(index)
            shownIndex = index
        }
        root.setOpacity(opacity)
    }

    private fun build(index: Int): Entity {
        val g = Entity().named("still$index")
        root.addChild(g)
        for ((k, s) in StillLife.sets[index].withIndex()) {
            val e: Entity =
                when (s) {
                    is Solid.Ball -> model(meshes.sphere, m.gypsum, "s${index}_$k").also { it.setLocal(position = Vec3(s.x, s.d / 2f, s.z), scale = Vec3(s.d / 2f, s.d / 2f, s.d / 2f)) }
                    is Solid.Cube ->
                        model(meshes.cube, m.gypsum, "s${index}_$k").also {
                            it.setLocal(position = Vec3(s.x, s.size / 2f, s.z), rotation = turn(s.turnDeg), scale = Vec3(s.size, s.size, s.size))
                        }
                    is Solid.Cylinder ->
                        if (s.lying) {
                            val q = turn(s.turnDeg) * Quat4.axisAngle(Vec3(0f, 0f, 1f), (PI / 2).toFloat())
                            model(meshes.cylinder, m.gypsum, "s${index}_$k").also {
                                it.setLocal(position = Vec3(s.x, s.d / 2f, s.z) + q.rotate(Vec3(0f, -s.h / 2f, 0f)), rotation = q, scale = Vec3(s.d / 2f, s.h, s.d / 2f))
                            }
                        } else {
                            model(meshes.cylinder, m.gypsum, "s${index}_$k").also { it.setLocal(position = Vec3(s.x, 0f, s.z), scale = Vec3(s.d / 2f, s.h, s.d / 2f)) }
                        }
                    is Solid.Cone -> {
                        val cone = shared.keep(MeshFactory.frustum(0.02f, "cone$index$k"))
                        model(cone, m.gypsum, "s${index}_$k").also { it.setLocal(position = Vec3(s.x, 0f, s.z), scale = Vec3(s.d / 2f, s.h, s.d / 2f)) }
                    }
                    is Solid.Block ->
                        model(meshes.cube, m.gypsum, "s${index}_$k").also {
                            it.setLocal(position = Vec3(s.x, s.y + s.h / 2f, s.z), rotation = turn(s.turnDeg), scale = Vec3(s.w, s.h, s.dz))
                        }
                    is Solid.Torus -> {
                        val minor = (s.outer - s.inner) / 4f
                        val major = s.inner / 2f + minor
                        val torus = shared.keep(MeshFactory.torus(major, minor, "torus$index$k"))
                        // standing on edge
                        val q = turn(s.turnDeg) * Quat4.axisAngle(Vec3.RIGHT, (PI / 2).toFloat())
                        model(torus, m.gypsum, "s${index}_$k").also { it.setLocal(position = Vec3(s.x, s.outer / 2f, s.z), rotation = q) }
                    }
                }
            e.castShadow()
            g.addChild(e)
        }
        return g
    }

    private fun turn(deg: Float): Quat4 = Quat4.axisAngle(Vec3.UP, Math.toRadians(deg.toDouble()).toFloat())

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/**
 * Stand ring: dashed circle, heading line and arrow head in interaction yellow, 2 mm above the surface
 * (design `entities3d.standFrame`). The same geometry serves as the relocation ghost.
 */
internal class RingView(parent: Entity, private val m: SceneMaterials, meshes: FmMeshes, private val ghost: Boolean) {
    val root: Entity = Entity().named(if (ghost) "ghostRing" else "standRing")
    private val circle = model(meshes.ring, if (ghost) m.ghost else m.ring, "ring_circle")
    private val bar = model(meshes.cube, if (ghost) m.ghost else m.ring, "ring_bar")
    private val head = model(meshes.arrowHead, if (ghost) m.ghost else m.ring, "ring_head")
    private var current: Material? = null
    private var arrowCurrent: Material? = null
    private var smallSize: Boolean? = null

    init {
        parent.addChild(root)
        root.addChild(circle)
        root.addChild(bar)
        root.addChild(head)
        root.enabled = false
    }

    fun place(at: Vec3, yaw: Float, smallSize: Boolean) {
        if (!root.enabled) root.enabled = true
        root.setLocal(position = at + Vec3(0f, 0.002f, 0f), rotation = Quat4.axisAngle(Vec3.UP, yaw))
        if (this.smallSize != smallSize) {
            this.smallSize = smallSize
            val k = if (smallSize) SMALL_SCALE else 1f
            circle.setLocal(scale = Vec3(k, 1f, k))
            val len = if (smallSize) 0.10f else 0.25f
            val tri = if (smallSize) 0.035f else 0.06f
            bar.setLocal(position = Vec3(0f, 0f, len / 2f), scale = Vec3(0.02f, 0.002f, len))
            head.setLocal(position = Vec3(0f, 0.001f, len), scale = Vec3(tri, 1f, tri))
        }
    }

    /** Ring and arrow materials: normal / 25 % / green flash (or the ghost's error red). */
    fun look(ring: Material, arrow: Material = ring) {
        if (current !== ring) {
            current = ring
            circle.setMaterial(ring)
        }
        if (arrowCurrent !== arrow) {
            arrowCurrent = arrow
            bar.setMaterial(arrow)
            head.setMaterial(arrow)
        }
    }

    fun hide() {
        if (root.enabled) root.enabled = false
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    private companion object {
        /** The small 0.3 m ring = the 0.9 m mesh at a third. */
        const val SMALL_SCALE = 1f / 3f
    }
}

/**
 * Timer ring at the figure's feet (design `entities3d.timerRing`): charcoal track at 20 % and an ink arc of 72
 * pieces that is used up clockwise (seen from above) from the figure's front; alert orange in the last five
 * seconds; hidden for ∞. Moves and turns with the figure.
 */
internal class TimerRingView(parent: Entity, private val m: SceneMaterials, meshes: FmMeshes) {
    val root: Entity = Entity().named("timerRing")
    private val track = model(meshes.track, m.track, "timer_track")
    private val pieces = ArrayList<Entity>(FmMeshes.TIMER_PIECES)
    private var lit = -1
    private var alert: Boolean? = null

    init {
        parent.addChild(root)
        root.addChild(track)
        val step = 2 * PI / FmMeshes.TIMER_PIECES
        for (k in 0 until FmMeshes.TIMER_PIECES) {
            val e = model(meshes.timerPiece, m.arc, "timer_$k")
            // piece k spans [90 + 5k, 95 + 5k] degrees from +X towards +Z: clockwise from the front (+Z) seen from above
            e.setLocal(position = Vec3(0f, 0.001f, 0f), rotation = Quat4.axisAngle(Vec3.UP, -(PI / 2 + step * k).toFloat()))
            root.addChild(e)
            pieces += e
        }
        root.enabled = false
    }

    /** [share] = remaining share 0..1; [scale] 1 or 0.11 / 0.38 for the small ring; [opacity] for the pause breathing. */
    fun show(at: Vec3, yaw: Float, scale: Float, share: Float, alertArc: Boolean, opacity: Float) {
        if (!root.enabled) root.enabled = true
        root.setLocal(position = at + Vec3(0f, 0.003f, 0f), rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(scale, 1f, scale))
        val n = FmMeshes.TIMER_PIECES
        val on = Math.round(share.coerceIn(0f, 1f) * n)
        if (on != lit) {
            lit = on
            // used up from the start: the remaining arc is the last [on] pieces
            for ((k, e) in pieces.withIndex()) {
                val shown = k >= n - on
                if (e.enabled != shown) e.enabled = shown
            }
        }
        if (alert != alertArc) {
            alert = alertArc
            val mat = if (alertArc) m.arcAlert else m.arc
            pieces.forEach { it.setMaterial(mat) }
        }
        root.setOpacity(opacity)
    }

    fun hide() {
        if (root.enabled) root.enabled = false
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** The light orb Ø 8 cm with a 0.30 m beam towards the orbit centre (design `entities3d.lightOrb`). */
internal class OrbView(parent: Entity, m: SceneMaterials, meshes: FmMeshes) {
    val root: Entity = Entity().named("orb")
    private val ball = model(meshes.sphere, m.orb, "orb_ball")
    private val beam = model(meshes.cylinder, m.beam, "orb_beam")
    val grab: Entity = Entity().named("orb_grab")

    init {
        parent.addChild(root)
        ball.setLocal(scale = Vec3(0.04f, 0.04f, 0.04f))
        root.addChild(ball)
        root.addChild(beam)
        grab.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(0.07f)), physicsMaterial = PhysicsMaterialResource()))
        grab.components.set(InteractableComponent())
        runCatching { grab.components.set(HoverEffectComponent()) }
        root.addChild(grab)
        root.enabled = false
    }

    fun show(at: Vec3, toward: Vec3, big: Boolean) {
        if (!root.enabled) root.enabled = true
        root.setLocal(position = at)
        val k = if (big) 0.048f else 0.04f
        ball.setLocal(scale = Vec3(k, k, k))
        val d = (toward - at).normalized()
        beam.setLocal(position = d * 0.045f, rotation = Quat4.fromTo(Vec3.UP, d), scale = Vec3(0.003f, 0.30f, 0.003f))
    }

    fun hide() {
        if (root.enabled) root.enabled = false
    }

    fun isGrab(e: Entity): Boolean = root.enabled && (e.id == grab.id || e.id == ball.id)

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/**
 * The "next" disc beside the figure (design `entities3d.nextDisc`): charcoal disc Ø 12 cm with a white
 * play-to-bar icon and an interaction-yellow progress rim. Local frame: +Y towards the head, +Z the
 * viewer's right, +X up.
 */
internal class DiscView(parent: Entity, m: SceneMaterials, meshes: FmMeshes) {
    val root: Entity = Entity().named("nextDisc")
    private val pieces = ArrayList<Entity>(FmMeshes.DISC_PIECES)
    val grab: Entity = Entity().named("disc_grab")
    private var lit = -1

    init {
        parent.addChild(root)
        root.addChild(model(meshes.disc, m.disc, "disc_face"))
        // icon 6 cm: a triangle pointing right and a bar after it
        root.addChild(model(meshes.arrowHead, m.discIcon, "disc_tri").also { it.setLocal(position = Vec3(0f, 0.002f, -0.022f), scale = Vec3(0.03f, 1f, 0.034f)) })
        root.addChild(model(meshes.cube, m.discIcon, "disc_bar").also { it.setLocal(position = Vec3(0f, 0.002f, 0.020f), scale = Vec3(0.036f, 0.001f, 0.008f)) })
        val step = 2 * PI / FmMeshes.DISC_PIECES
        for (k in 0 until FmMeshes.DISC_PIECES) {
            val e = model(meshes.discPiece, m.progress, "disc_p$k")
            // from the top (+X) clockwise as the viewer sees it (towards +Z)
            e.setLocal(position = Vec3(0f, 0.003f, 0f), rotation = Quat4.axisAngle(Vec3.UP, -(step * k).toFloat()))
            e.enabled = false
            root.addChild(e)
            pieces += e
        }
        grab.tapBox(Vec3(0.15f, 0.03f, 0.15f))
        runCatching { grab.components.set(HoverEffectComponent()) }
        root.addChild(grab)
        root.enabled = false
    }

    /** [toHead] unit vector from the disc to the head; [progress] 0..1. */
    fun show(at: Vec3, toHead: Vec3, viewerRight: Vec3, progress: Float, scale: Float) {
        if (!root.enabled) root.enabled = true
        val y = toHead.normalized()
        var z = viewerRight.rejectFrom(y)
        if (z.length < 1e-3f) z = Vec3.RIGHT.rejectFrom(y)
        z = z.normalized()
        val x = y.cross(z).normalized()
        root.setLocal(position = at, rotation = Quat4.fromBasis(x, y, z), scale = Vec3(scale, scale, scale))
        val on = Math.round(progress.coerceIn(0f, 1f) * FmMeshes.DISC_PIECES)
        if (on != lit) {
            lit = on
            for ((k, e) in pieces.withIndex()) {
                val shown = k < on
                if (e.enabled != shown) e.enabled = shown
            }
        }
    }

    fun hide() {
        if (root.enabled) root.enabled = false
    }

    fun isGrab(e: Entity): Boolean = root.enabled && e.id == grab.id

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}
