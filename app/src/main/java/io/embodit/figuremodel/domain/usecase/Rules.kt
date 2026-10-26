package io.embodit.figuremodel.domain.usecase

import io.embodit.figuremodel.domain.model.LightPreset
import io.embodit.figuremodel.domain.model.PlaneSurface
import io.embodit.figuremodel.domain.model.SurfaceKind
import io.embodit.figuremodel.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Horizontal unit vector of [v] (or [fallback] when [v] is nearly vertical). */
fun flat(v: Vec3, fallback: Vec3 = Vec3.AHEAD): Vec3 {
    val f = Vec3(v.x, 0f, v.z)
    return if (f.length < 1e-3f) fallback else f.normalized()
}

/** Yaw (radians, about +Y) that turns the figure's front (+Z) towards the horizontal direction [dir]. */
fun yawFacing(dir: Vec3): Float = atan2(dir.x, dir.z)

/** Front (+Z) of a figure turned by [yaw]. */
fun frontOf(yaw: Float): Vec3 = Vec3(sin(yaw), 0f, cos(yaw))

/** Smallest signed difference a - b of two angles, radians, in -PI..PI. */
fun angleDiff(a: Float, b: Float): Float {
    var d = (a - b) % (2f * Math.PI.toFloat())
    if (d > Math.PI) d -= 2f * Math.PI.toFloat()
    if (d < -Math.PI) d += 2f * Math.PI.toFloat()
    return d
}

/**
 * The light (design `rules.light`): a direction given as azimuth / elevation around the orbit centre.
 * Azimuth 0 is the figure's front at the moment of placement ([front0]), positive towards the viewer's
 * right, so the light stays fixed in the world while the viewer walks round or turns the figure.
 */
object LightRule {
    const val EL_MIN = 10f
    const val EL_MAX = 80f
    const val ORBIT_FULL_M = 1.4f
    const val ORBIT_DESK_M = 0.6f
    const val ORB_MAX_HEIGHT_M = 2.2f
    const val ORB_MIN_HEAD_M = 0.6f

    /** Unit vector from the orbit centre towards the light. */
    fun direction(front0: Vec3, azDeg: Float, elDeg: Float): Vec3 {
        val f = flat(front0)
        val right = (-f).cross(Vec3.UP).normalized()
        val az = Math.toRadians(azDeg.toDouble())
        val el = Math.toRadians(elDeg.toDouble())
        val h = f * cos(az).toFloat() + right * sin(az).toFloat()
        return (h * cos(el).toFloat() + Vec3.UP * sin(el).toFloat()).normalized()
    }

    /** Azimuth of a horizontal direction [dir] in the frame of [front0], degrees -180..180. */
    fun azimuthOf(front0: Vec3, dir: Vec3): Float {
        val f = flat(front0)
        val right = (-f).cross(Vec3.UP).normalized()
        val h = flat(dir, f)
        return Math.toDegrees(atan2(h.dot(right), h.dot(f)).toDouble()).toFloat()
    }

    /**
     * A preset, computed once from where the viewer stands now ([towardViewer] = figure -> head,
     * horizontal): left / right 45 degrees of the viewer at 45 up, front at 30 up, back light at 60 up, top 80 up.
     */
    fun preset(preset: LightPreset, front0: Vec3, towardViewer: Vec3, currentAz: Float): Pair<Float, Float> {
        val v = flat(towardViewer, flat(front0))
        val viewerRight = (-v).cross(Vec3.UP).normalized()
        fun turned(deg: Float): Vec3 {
            val r = Math.toRadians(deg.toDouble())
            return v * cos(r).toFloat() + viewerRight * sin(r).toFloat()
        }
        return when (preset) {
            LightPreset.LEFT -> azimuthOf(front0, turned(-45f)) to 45f
            LightPreset.RIGHT -> azimuthOf(front0, turned(45f)) to 45f
            LightPreset.FRONT -> azimuthOf(front0, v) to 30f
            LightPreset.BACK -> azimuthOf(front0, -v) to 60f
            LightPreset.TOP -> currentAz to 80f
        }
    }

    fun wrapAz(az: Float): Float {
        var a = az % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    /**
     * Where the orb is drawn: on the nominal orbit, pulled in along the same direction so that its centre is
     * at most 2.2 m above the floor and at least 0.6 m from the head. The light direction is not affected.
     */
    fun orbPosition(center: Vec3, dir: Vec3, nominal: Float, floorY: Float, head: Vec3?): Vec3 {
        var r = nominal
        if (dir.y > 1e-3f) {
            val maxR = (floorY + ORB_MAX_HEIGHT_M - center.y) / dir.y
            if (maxR < r) r = maxR.coerceAtLeast(0.15f)
        }
        if (head != null) {
            var guard = 0
            while (guard++ < 40 && r > 0.15f && (center + dir * r).distanceTo(head) < ORB_MIN_HEAD_M) r -= 0.03f
        }
        return center + dir * r
    }
}

/**
 * Order of the poses (design `rules.sequence`): shuffled rounds without repeats (a new round never starts
 * with the pose that ended the last one), or 1 -> N in order. Back / forward walk the history.
 */
class PoseSequence(private val count: Int, private val random: Random = Random.Default) {
    private class Step(val index: Int, val roundPos: Int)

    private val history = ArrayList<Step>()
    private var cursor = -1
    private val bag = ArrayList<Int>()
    var shuffle: Boolean = true
        private set

    val current: Int
        get() = history.getOrNull(cursor)?.index ?: 0

    /** 1-based position in the current round (shuffle) or the pose number (in order). */
    val position: Int
        get() = history.getOrNull(cursor)?.roundPos ?: 1

    fun start(shuffle: Boolean) {
        this.shuffle = shuffle
        history.clear()
        bag.clear()
        cursor = -1
        next()
    }

    fun setShuffle(value: Boolean) {
        if (value == shuffle) return
        shuffle = value
        bag.clear()
        // keep the current pose, drop the forward history
        val keep = history.getOrNull(cursor)
        history.clear()
        if (keep != null) history += Step(keep.index, if (value) 1 else keep.index + 1)
        cursor = history.size - 1
        if (value) refill(exclude = keep?.index)
        keep?.let { bag.remove(it.index) }
    }

    fun next(): Int {
        if (cursor < history.size - 1) {
            cursor++
            return current
        }
        val last = history.lastOrNull()?.index
        val step =
            if (shuffle) {
                if (bag.isEmpty()) refill(exclude = last)
                val i = bag.removeAt(0)
                Step(i, count - bag.size)
            } else {
                val i = if (last == null) 0 else (last + 1) % count
                Step(i, i + 1)
            }
        history += step
        if (history.size > MAX_HISTORY) history.removeAt(0)
        cursor = history.size - 1
        return current
    }

    fun previous(): Int {
        if (cursor > 0) cursor-- else if (!shuffle) {
            val i = (current - 1 + count) % count
            history.add(0, Step(i, i + 1))
            cursor = 0
        }
        return current
    }

    /** Jumps to pose [index] (debug), as the next step. */
    fun jump(index: Int): Int {
        val i = index.coerceIn(0, count - 1)
        bag.remove(i)
        while (history.size - 1 > cursor) history.removeAt(history.size - 1)
        history += Step(i, if (shuffle) (count - bag.size).coerceAtLeast(1) else i + 1)
        cursor = history.size - 1
        return i
    }

    /** The order of the current round, for the debug dump. */
    fun upcoming(): List<Int> = bag.toList()

    private fun refill(exclude: Int?) {
        bag.clear()
        bag.addAll((0 until count).shuffled(random))
        if (exclude != null && bag.size > 1 && bag[0] == exclude) {
            bag.removeAt(0)
            bag.add(exclude)
        }
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}

/** Where the ring goes (design `rules.autoPlace`, `rules.relocate`). */
object PlaceRule {
    const val FULL_AHEAD_M = 1.8f
    const val DESK_AHEAD_M = 1.0f
    const val EDGE_MARGIN_M = 0.3f

    /** The floor: the largest FLOOR plane (by hull area). */
    fun floor(planes: Collection<PlaneSurface>): PlaneSurface? =
        planes.filter { it.kind == SurfaceKind.FLOOR }.maxByOrNull { Flat.polygonArea(Flat(it).hull) }

    fun floorY(planes: Collection<PlaneSurface>): Float? {
        val f = floor(planes) ?: return null
        return f.vertices.map { it.y }.average().toFloat()
    }

    /**
     * Ring centre [ahead] metres in front of the head's floor projection, clamped 0.3 m inside the floor
     * polygon; facing the head.
     */
    fun autoPlace(head: Vec3, forward: Vec3, floor: PlaneSurface, ahead: Float): Pair<Vec3, Float> {
        val y = floor.vertices.map { it.y }.average().toFloat()
        val f = flat(forward)
        var p = Vec3(head.x, y, head.z) + f * ahead
        val chart = Flat(floor)
        if (chart.hull.size >= 3 && !chart.contains(p, EDGE_MARGIN_M)) {
            val q = chart.to3(chart.clampInside(chart.to2(p), EDGE_MARGIN_M))
            p = Vec3(q.x, y, q.z)
        }
        return p to yawFacing(flat(head - p))
    }

    /** Signed distance of a point from the floor polygon's edge (positive inside); null without a floor. */
    fun insetOnFloor(floor: PlaneSurface?, p: Vec3): Float? {
        if (floor == null) return null
        val chart = Flat(floor)
        if (chart.hull.size < 3) return null
        return chart.signedInset(chart.to2(p))
    }

    /** True when the horizontal plane under [p] (within 5 cm vertically) is a TABLE. */
    fun isTable(plane: PlaneSurface?): Boolean = plane?.kind == SurfaceKind.TABLE

    fun near(a: Float, b: Float, eps: Float): Boolean = abs(a - b) <= eps
}
