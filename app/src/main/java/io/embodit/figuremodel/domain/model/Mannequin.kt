package io.embodit.figuremodel.domain.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The drawn parts of the wooden mannequin (design `entities3d.mannequin.rig.segments`). */
enum class Part(val kind: Kind) {
    PELVIS(Kind.ELLIPSOID),
    WAIST(Kind.BALL),
    CHEST(Kind.ELLIPSOID),
    NECK(Kind.NECK),
    NECK_BALL(Kind.BALL),
    HEAD(Kind.ELLIPSOID),
    R_SHOULDER(Kind.BALL), R_UPPER_ARM(Kind.UPPER_ARM), R_ELBOW(Kind.BALL), R_FOREARM(Kind.FOREARM), R_WRIST(Kind.BALL), R_HAND(Kind.HAND),
    L_SHOULDER(Kind.BALL), L_UPPER_ARM(Kind.UPPER_ARM), L_ELBOW(Kind.BALL), L_FOREARM(Kind.FOREARM), L_WRIST(Kind.BALL), L_HAND(Kind.HAND),
    R_HIP(Kind.BALL), R_THIGH(Kind.THIGH), R_KNEE(Kind.BALL), R_SHIN(Kind.SHIN), R_ANKLE(Kind.BALL), R_FOOT(Kind.FOOT),
    L_HIP(Kind.BALL), L_THIGH(Kind.THIGH), L_KNEE(Kind.BALL), L_SHIN(Kind.SHIN), L_ANKLE(Kind.BALL), L_FOOT(Kind.FOOT);

    /**
     * Mesh family: ELLIPSOID / BALL = unit sphere scaled; the four limb kinds = a frustum of radius 1 at the
     * root and [Kind.taper] at the tip, length 1 along +Y; HAND / FOOT = the unit cube scaled to their size.
     */
    enum class Kind(val taper: Float) {
        ELLIPSOID(1f),
        BALL(1f),
        NECK(0.89f),
        UPPER_ARM(0.78f),
        FOREARM(0.71f),
        THIGH(0.71f),
        SHIN(0.70f),
        HAND(1f),
        FOOT(1f),
    }

    /** Joint balls are the darker wood (design `material`: woodDark). */
    val joint: Boolean
        get() = kind == Kind.BALL
}

/** Where one part goes, in the figure's own frame (origin on the floor between the ankles, front = +Z, up = +Y). */
data class PartPose(val position: Vec3, val rotation: Quat4, val scale: Vec3)

/** A solved pose. */
class FigureFrame(
    val parts: Array<PartPose>,
    /** Chest centre (light orbit centre of the life-size figure). */
    val chest: Vec3,
    val headTop: Float,
    /** Underside of the pelvis: the stool top of the seated pose. */
    val pelvisBottom: Float,
    /** Hands, elbows, knees, feet and head: the disc keeps clear of these (design `rules.dwell`). */
    val limbs: List<Vec3>,
    val min: Vec3,
    val max: Vec3,
)

/**
 * Forward kinematics of the wooden mannequin, a line-by-line port of the approved mockup (`sample.html`,
 * `buildFigure`), at the design's art proportions (7.5 heads, 1.75 m): pelvis centre 0.95 m before grounding,
 * shoulders 0.38 m and hips -0.05 m from the waist ball, upper arm 0.30, forearm 0.27, hand 0.17,
 * thigh 0.42, shin 0.41. The figure's right side is -X. Plain JVM: no SDK types.
 */
object Mannequin {
    const val HEIGHT_M = 1.75f

    /** Knee flex added at most to level the feet of a both-feet pose (design `rules.feetOnFloor`). */
    private const val MAX_KNEE_FIX_DEG = 15f
    private const val FEET_LEVEL_M = 0.02f

    /** Index -> degrees added by [settle] (for the start-up log line). */
    val kneeFixes = LinkedHashMap<Int, Float>()

    /** Angles of [def] clamped to the joint limits and, for both-feet poses, with the feet levelled. */
    fun settle(def: PoseDef): FloatArray {
        val a = def.angles.copyOf()
        J.clamp(a)
        // support feet without an ankle value in the table stand flat: bending a knee alone tips the toes down
        // instead of lifting the foot (found by the unit test on pose 2)
        val rightFlat = def.angles[J.R_ANK] == 0f && def.ground in RIGHT_SUPPORT
        val leftFlat = def.angles[J.L_ANK] == 0f && def.ground in LEFT_SUPPORT
        fun flatten() {
            if (rightFlat) a[J.R_ANK] = (a[J.R_HIP_FLEX] - a[J.R_KNEE]).coerceIn(-40f, 70f)
            if (leftFlat) a[J.L_ANK] = (a[J.L_HIP_FLEX] - a[J.L_KNEE]).coerceIn(-40f, 70f)
        }
        flatten()
        if (def.ground == Ground.BOTH) {
            // per knee at most 15 degrees (design `rules.feetOnFloor`); each step one degree on one knee
            val used = HashMap<Int, Float>()
            var steps = 0
            while (steps++ < 2 * MAX_KNEE_FIX_DEG.toInt()) {
                val (r, l) = soles(a)
                val gap = abs(r - l)
                if (gap <= FEET_LEVEL_M) break
                // bend the leg with the lower sole, or straighten the other one, whichever closes the gap more
                // (an ankle at its limit cannot follow a bend)
                val lowerKnee = if (r < l) J.R_KNEE else J.L_KNEE
                val higherKnee = if (r < l) J.L_KNEE else J.R_KNEE
                val options = ArrayList<Pair<Int, FloatArray>>(2)
                if ((used[lowerKnee] ?: 0f) < MAX_KNEE_FIX_DEG) options += lowerKnee to a.copyOf().also { it[lowerKnee] += 1f }
                if ((used[higherKnee] ?: 0f) < MAX_KNEE_FIX_DEG && a[higherKnee] >= 1f) options += higherKnee to a.copyOf().also { it[higherKnee] -= 1f }
                if (options.isEmpty()) break
                fun gapOf(t: FloatArray): Float {
                    val saved = a.copyOf()
                    t.copyInto(a)
                    flatten()
                    val (rr, ll) = soles(a)
                    saved.copyInto(a)
                    return abs(rr - ll)
                }
                val best = options.minByOrNull { gapOf(it.second) }!!
                if (gapOf(best.second) >= gap) break
                best.second.copyInto(a)
                flatten()
                used[best.first] = (used[best.first] ?: 0f) + 1f
            }
            val total = used.values.sum()
            if (total > 0f) kneeFixes[def.n] = total
        }
        return a
    }

    private val FOOT_X = floatArrayOf(-0.045f, 0.045f)
    private val FOOT_Y = floatArrayOf(-0.08f, 0f)
    private val FOOT_Z = floatArrayOf(-0.05f, 0.20f)

    private val RIGHT_SUPPORT = setOf(Ground.BOTH, Ground.BOTH_TIPTOE)
    private val LEFT_SUPPORT = setOf(Ground.BOTH, Ground.BOTH_TIPTOE, Ground.LEFT)

    /** Lowest sole point of each foot before grounding (right, left). */
    private fun soles(a: FloatArray): Pair<Float, Float> {
        val f = raw(a)
        return min(f.soles[0].y, f.soles[1].y) to min(f.soles[2].y, f.soles[3].y)
    }

    fun solve(a: FloatArray): FigureFrame {
        val f = raw(a)
        val low = ArrayList<Vec3>(8)
        low.addAll(f.soles)
        low.addAll(f.kneeLows)
        low.addAll(f.footTops)
        low += f.pelvisLow
        val dy = -low.minOf { it.y } + a[J.LIFT]
        // pivot: the vertical through the middle of the ankles
        val mid = (f.ankles[0] + f.ankles[1]) * 0.5f
        val shift = Vec3(-mid.x, dy, -mid.z)
        val parts = Array(f.parts.size) { i -> f.parts[i].let { PartPose(it.position + shift, it.rotation, it.scale) } }
        var mn = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var mx = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in parts) {
            val r = max(p.scale.x, max(p.scale.y, p.scale.z)).coerceAtMost(0.2f)
            mn = Vec3(min(mn.x, p.position.x - r), min(mn.y, p.position.y - r), min(mn.z, p.position.z - r))
            mx = Vec3(max(mx.x, p.position.x + r), max(mx.y, p.position.y + r), max(mx.z, p.position.z + r))
        }
        return FigureFrame(
            parts = parts,
            chest = f.chest + shift,
            headTop = f.headTop + dy,
            pelvisBottom = f.pelvisLow.y + dy,
            limbs = f.limbs.map { it + shift },
            min = mn,
            max = mx,
        )
    }

    private class Raw(
        val parts: Array<PartPose>,
        val soles: List<Vec3>,
        val kneeLows: List<Vec3>,
        /** All corners of the foot boxes: the kneeling pose rests on an instep, the cross-legged one on the outer edges. */
        val footTops: List<Vec3>,
        val pelvisLow: Vec3,
        val ankles: List<Vec3>,
        val chest: Vec3,
        val headTop: Float,
        val limbs: List<Vec3>,
    )

    private fun raw(a: FloatArray): Raw {
        val parts = arrayOfNulls<PartPose>(Part.entries.size)
        fun put(part: Part, pose: PartPose) {
            parts[part.ordinal] = pose
        }
        fun ball(part: Part, c: Vec3, r: Float) = put(part, PartPose(c, Quat4.IDENTITY, Vec3(r, r, r)))
        fun ellipsoid(part: Part, c: Vec3, m: M3, ax: Float, ay: Float, az: Float) = put(part, PartPose(c, m.quat(), Vec3(ax, ay, az)))
        fun limb(part: Part, from: Vec3, to: Vec3, r: Float) {
            val d = to - from
            val len = d.length
            val rot = if (len < 1e-5f) Quat4.IDENTITY else Quat4.fromTo(Vec3.UP, d / len)
            put(part, PartPose(from, rot, Vec3(r, len, r)))
        }

        val mr = ry(a[J.ROOT_YAW]) * rz(a[J.ROOT_ROLL]) * rx(a[J.ROOT_PITCH])
        val root = Vec3(0f, 0.95f, 0f)
        ellipsoid(Part.PELVIS, root, mr, 0.17f, 0.10f, 0.10f)
        val waist = root + mr * Vec3(0f, 0.10f, 0f)
        ball(Part.WAIST, waist, 0.06f)
        val mc = mr * (rx(a[J.WAIST_FWD]) * rz(a[J.WAIST_SIDE]) * ry(a[J.WAIST_TWIST]))
        val chest = waist + mc * Vec3(0f, 0.22f, 0f)
        ellipsoid(Part.CHEST, chest, mc, 0.18f, 0.16f, 0.10f)
        val neckBase = waist + mc * Vec3(0f, 0.40f, 0f)
        val mn = mc * (rx(a[J.NECK_FWD]) * rz(a[J.NECK_SIDE]) * ry(a[J.NECK_TWIST]))
        limb(Part.NECK, neckBase, neckBase + mn * Vec3(0f, 0.08f, 0f), 0.045f)
        ball(Part.NECK_BALL, neckBase + mn * Vec3(0f, 0.02f, 0f), 0.035f)
        val head = neckBase + mn * Vec3(0f, 0.19f, 0f)
        ellipsoid(Part.HEAD, head, mn, 0.08f, 0.115f, 0.095f)
        val limbs = ArrayList<Vec3>(11)
        limbs += head

        // arms: right (sign -1, x = -0.20), left (sign +1)
        for (right in listOf(true, false)) {
            val sg = if (right) -1f else 1f
            val sh = waist + mc * Vec3(sg * 0.20f, 0.38f, 0f)
            val flex = a[if (right) J.R_SH_FLEX else J.L_SH_FLEX]
            val abd = a[if (right) J.R_SH_ABD else J.L_SH_ABD]
            val rot = a[if (right) J.R_SH_ROT else J.L_SH_ROT]
            val yaw = a[if (right) J.R_SH_YAW else J.L_SH_YAW]
            val elbow = a[if (right) J.R_EL else J.L_EL]
            val ma = mc * (ry(yaw) * rz(sg * abd) * rx(-flex) * ry(rot))
            val el = sh + ma * Vec3(0f, -0.30f, 0f)
            val mf = ma * rx(-elbow)
            val wr = el + mf * Vec3(0f, -0.27f, 0f)
            val handCenter = wr + mf * Vec3(0f, -0.095f, 0f)
            ball(if (right) Part.R_SHOULDER else Part.L_SHOULDER, sh, 0.04f)
            limb(if (right) Part.R_UPPER_ARM else Part.L_UPPER_ARM, sh, el, 0.045f)
            ball(if (right) Part.R_ELBOW else Part.L_ELBOW, el, 0.03f)
            limb(if (right) Part.R_FOREARM else Part.L_FOREARM, el, wr, 0.035f)
            ball(if (right) Part.R_WRIST else Part.L_WRIST, wr, 0.025f)
            put(if (right) Part.R_HAND else Part.L_HAND, PartPose(handCenter, mf.quat(), HAND_SIZE))
            limbs += el
            limbs += wr + mf * Vec3(0f, -0.17f, 0f)
        }

        // legs
        val soles = ArrayList<Vec3>(4)
        val kneeLows = ArrayList<Vec3>(2)
        val footTops = ArrayList<Vec3>(16)
        val ankles = ArrayList<Vec3>(2)
        for (right in listOf(true, false)) {
            val sg = if (right) -1f else 1f
            val hp = root + mr * Vec3(sg * 0.11f, -0.05f, 0f)
            val flex = a[if (right) J.R_HIP_FLEX else J.L_HIP_FLEX]
            val abd = a[if (right) J.R_HIP_ABD else J.L_HIP_ABD]
            val rot = a[if (right) J.R_HIP_ROT else J.L_HIP_ROT]
            val knee = a[if (right) J.R_KNEE else J.L_KNEE]
            val ank = a[if (right) J.R_ANK else J.L_ANK]
            // flex first, then spread: with the mockup's order (spread first) a hip flexed 90 degrees cannot open
            // sideways and the cross-legged pose floats on its feet; identical for every pose with straight hips
            val mt = mr * (rx(-flex) * rz(sg * abd) * ry(sg * rot))
            val kn = hp + mt * Vec3(0f, -0.42f, 0f)
            val ms = mt * rx(knee)
            val an = kn + ms * Vec3(0f, -0.41f, 0f)
            val mfo = ms * rx(ank)
            ball(if (right) Part.R_HIP else Part.L_HIP, hp, 0.04f)
            limb(if (right) Part.R_THIGH else Part.L_THIGH, hp, kn, 0.07f)
            ball(if (right) Part.R_KNEE else Part.L_KNEE, kn, 0.03f)
            limb(if (right) Part.R_SHIN else Part.L_SHIN, kn, an, 0.05f)
            ball(if (right) Part.R_ANKLE else Part.L_ANKLE, an, 0.025f)
            put(if (right) Part.R_FOOT else Part.L_FOOT, PartPose(an + mfo * Vec3(0f, -0.04f, 0.075f), mfo.quat(), FOOT_SIZE))
            soles += an + mfo * Vec3(0f, -0.08f, -0.05f)
            soles += an + mfo * Vec3(0f, -0.08f, 0.20f)
            kneeLows += kn + Vec3(0f, -0.035f, 0f)
            for (x in FOOT_X) for (y in FOOT_Y) for (z in FOOT_Z) footTops += an + mfo * Vec3(x, y, z)
            ankles += an
            limbs += kn
            limbs += an + mfo * Vec3(0f, -0.04f, 0.075f)
        }
        @Suppress("UNCHECKED_CAST")
        return Raw(
            parts = parts as Array<PartPose>,
            soles = soles,
            kneeLows = kneeLows,
            footTops = footTops,
            pelvisLow = root + Vec3(0f, -0.10f, 0f),
            ankles = ankles,
            chest = chest,
            headTop = head.y + 0.115f,
            limbs = limbs,
        )
    }

    /** Hand and foot boxes (metres): thickness across the palm, length, width; foot width, height, length. */
    val HAND_SIZE = Vec3(0.03f, 0.17f, 0.08f)
    val FOOT_SIZE = Vec3(0.09f, 0.08f, 0.25f)

    // ---- 3 x 3 rotation matrices, row major (the mockup's rx / ry / rz)
    class M3(val m: FloatArray) {
        operator fun times(o: M3): M3 {
            val r = FloatArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += m[i * 3 + k] * o.m[k * 3 + j]
                r[i * 3 + j] = s
            }
            return M3(r)
        }

        operator fun times(v: Vec3): Vec3 =
            Vec3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z, m[6] * v.x + m[7] * v.y + m[8] * v.z)

        /** The rotation as a quaternion (columns are the local axes). */
        fun quat(): Quat4 = Quat4.fromBasis(Vec3(m[0], m[3], m[6]), Vec3(m[1], m[4], m[7]), Vec3(m[2], m[5], m[8]))
    }

    private fun rad(d: Float) = Math.toRadians(d.toDouble())

    fun rx(d: Float): M3 {
        val c = cos(rad(d)).toFloat()
        val s = sin(rad(d)).toFloat()
        return M3(floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c))
    }

    fun ry(d: Float): M3 {
        val c = cos(rad(d)).toFloat()
        val s = sin(rad(d)).toFloat()
        return M3(floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c))
    }

    fun rz(d: Float): M3 {
        val c = cos(rad(d)).toFloat()
        val s = sin(rad(d)).toFloat()
        return M3(floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f))
    }
}
