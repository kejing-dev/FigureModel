package io.embodit.figuremodel.domain.model

/**
 * Joint angles of the mannequin as one flat array (degrees, the lift in metres), so that a transition is a
 * plain linear blend. Convention (design `rig.angleConvention`, same as the approved mockup `sample.html`):
 * flex = raise forwards, abd = raise sideways, rot = about the limb, knee flex bends backwards, ankle pf
 * positive points the toes down; waist / neck: fwd leans forwards, side bends, twist turns to the left.
 */
object J {
    const val ROOT_YAW = 0
    const val ROOT_ROLL = 1
    const val ROOT_PITCH = 2
    const val WAIST_FWD = 3
    const val WAIST_SIDE = 4
    const val WAIST_TWIST = 5
    const val NECK_FWD = 6
    const val NECK_SIDE = 7
    const val NECK_TWIST = 8
    const val R_SH_FLEX = 9
    const val R_SH_ABD = 10
    const val R_SH_ROT = 11
    const val R_SH_YAW = 12
    const val R_EL = 13
    const val L_SH_FLEX = 14
    const val L_SH_ABD = 15
    const val L_SH_ROT = 16
    const val L_SH_YAW = 17
    const val L_EL = 18
    const val R_HIP_FLEX = 19
    const val R_HIP_ABD = 20
    const val R_HIP_ROT = 21
    const val R_KNEE = 22
    const val R_ANK = 23
    const val L_HIP_FLEX = 24
    const val L_HIP_ABD = 25
    const val L_HIP_ROT = 26
    const val L_KNEE = 27
    const val L_ANK = 28
    const val LIFT = 29
    const val COUNT = 30

    fun blank(): FloatArray = FloatArray(COUNT)

    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(COUNT) { a[it] + (b[it] - a[it]) * t }

    /** Joint limits (design `rig.angleConvention`): index -> min..max. */
    private val LIMITS: Map<Int, ClosedFloatingPointRange<Float>> =
        mapOf(
            R_SH_FLEX to -60f..180f, L_SH_FLEX to -60f..180f,
            R_SH_ABD to 0f..180f, L_SH_ABD to 0f..180f,
            R_EL to 0f..150f, L_EL to 0f..150f,
            R_HIP_FLEX to -20f..120f, L_HIP_FLEX to -20f..120f,
            R_HIP_ABD to 0f..60f, L_HIP_ABD to 0f..60f,
            R_KNEE to 0f..140f, L_KNEE to 0f..140f,
            R_ANK to -40f..70f, L_ANK to -40f..70f,
        )

    /** Clamps to the limits; returns the indices that were out of range. */
    fun clamp(a: FloatArray): List<Int> {
        val out = ArrayList<Int>()
        for ((i, r) in LIMITS) {
            if (a[i] < r.start || a[i] > r.endInclusive) {
                out += i
                a[i] = a[i].coerceIn(r.start, r.endInclusive)
            }
        }
        return out
    }
}

/** Which feet touch the floor in a pose (design `poses.list[].ground`). */
enum class Ground {
    BOTH,
    BOTH_TIPTOE,
    LEFT,
    SEAT,
    KNEE,
    FLOOR,
    AIR,
}

/** One of the 20 poses (design `poses.list`). [prop] = "stool" for the seated pose. */
class PoseDef(val n: Int, val zh: String, val en: String, val ground: Ground, val prop: String?, val angles: FloatArray)

object Poses {
    private fun p(vararg pairs: Pair<Int, Float>): FloatArray = J.blank().also { a -> for ((i, v) in pairs) a[i] = v }

    /**
     * The table of the approved mockup (poses 4 and 20 with the tiptoe correction of the design role). Build
     * station change: pose 12 hip rotation 40 -> 75 degrees, so that the shins cross and the pelvis sits on the floor.
     */
    val all: List<PoseDef> =
        listOf(
            PoseDef(1, "正立", "Attention", Ground.BOTH, null, p(J.R_SH_ABD to 5f, J.L_SH_ABD to 5f)),
            PoseDef(2, "对立式", "Contrapposto", Ground.BOTH, null,
                p(J.R_KNEE to 15f, J.R_HIP_ABD to 8f, J.ROOT_ROLL to 6f, J.WAIST_SIDE to -4f, J.R_SH_ABD to 40f, J.R_SH_FLEX to -10f, J.R_SH_ROT to 90f, J.R_EL to 95f, J.L_SH_ABD to 6f, J.NECK_SIDE to 5f)),
            PoseDef(3, "叉腰站", "Hands on hips", Ground.BOTH, null,
                p(J.R_SH_ABD to 45f, J.R_SH_FLEX to -10f, J.R_SH_ROT to 90f, J.R_EL to 100f, J.L_SH_ABD to 45f, J.L_SH_FLEX to -10f, J.L_SH_ROT to -90f, J.L_EL to 100f, J.R_HIP_ABD to 10f, J.L_HIP_ABD to 10f)),
            PoseDef(4, "举手伸展", "Reach up", Ground.BOTH_TIPTOE, null,
                p(J.R_SH_FLEX to 170f, J.R_SH_ABD to 10f, J.R_EL to 5f, J.L_SH_ABD to 6f, J.WAIST_SIDE to -10f, J.R_HIP_FLEX to 35f, J.R_KNEE to 70f, J.R_ANK to 25f, J.NECK_FWD to -15f)),
            PoseDef(5, "弓步", "Lunge", Ground.BOTH, null,
                p(J.R_HIP_FLEX to 60f, J.R_KNEE to 90f, J.L_HIP_FLEX to -15f, J.L_KNEE to 45f, J.WAIST_FWD to 15f, J.R_SH_FLEX to 60f, J.R_EL to 20f, J.L_SH_FLEX to 60f, J.L_EL to 20f)),
            PoseDef(6, "单脚站", "One leg", Ground.LEFT, null,
                p(J.R_HIP_FLEX to 90f, J.R_KNEE to 90f, J.R_SH_ABD to 90f, J.L_SH_ABD to 90f, J.WAIST_SIDE to -5f)),
            PoseDef(7, "推", "Push", Ground.BOTH, null,
                p(J.R_SH_FLEX to 90f, J.R_EL to 20f, J.L_SH_FLEX to 90f, J.L_EL to 20f, J.WAIST_FWD to 20f, J.L_HIP_FLEX to 40f, J.L_KNEE to 50f, J.R_HIP_FLEX to -20f)),
            PoseDef(8, "回头看", "Look back", Ground.BOTH, null,
                p(J.WAIST_TWIST to 30f, J.NECK_TWIST to 40f, J.R_SH_FLEX to -30f, J.R_SH_ABD to 5f, J.L_SH_FLEX to 15f, J.L_SH_ABD to 5f, J.R_HIP_ROT to 10f)),
            PoseDef(9, "坐", "Sitting", Ground.SEAT, "stool",
                p(J.R_HIP_FLEX to 90f, J.R_KNEE to 90f, J.L_HIP_FLEX to 90f, J.L_KNEE to 90f, J.R_SH_FLEX to 30f, J.R_EL to 60f, J.L_SH_FLEX to 30f, J.L_EL to 60f, J.WAIST_FWD to 5f)),
            PoseDef(10, "蹲", "Squat", Ground.BOTH, null,
                p(J.R_HIP_FLEX to 110f, J.R_HIP_ABD to 15f, J.R_KNEE to 130f, J.R_ANK to -25f, J.L_HIP_FLEX to 110f, J.L_HIP_ABD to 15f, J.L_KNEE to 130f, J.L_ANK to -25f, J.WAIST_FWD to 25f, J.R_SH_FLEX to 45f, J.R_EL to 70f, J.L_SH_FLEX to 45f, J.L_EL to 70f)),
            PoseDef(11, "跪", "Kneeling", Ground.KNEE, null,
                p(J.R_HIP_FLEX to 0f, J.R_KNEE to 90f, J.R_ANK to 60f, J.L_HIP_FLEX to 90f, J.L_KNEE to 90f, J.WAIST_FWD to 10f, J.R_SH_FLEX to 80f, J.R_EL to 20f, J.L_SH_ABD to 6f)),
            PoseDef(12, "盘坐", "Cross-legged", Ground.FLOOR, null,
                p(J.R_HIP_FLEX to 90f, J.R_HIP_ABD to 45f, J.R_HIP_ROT to 75f, J.R_KNEE to 120f, J.L_HIP_FLEX to 90f, J.L_HIP_ABD to 45f, J.L_HIP_ROT to 75f, J.L_KNEE to 120f, J.R_SH_FLEX to 25f, J.R_EL to 70f, J.L_SH_FLEX to 25f, J.L_EL to 70f, J.WAIST_FWD to 5f)),
            PoseDef(13, "跑", "Running", Ground.AIR, null,
                p(J.LIFT to 0.05f, J.R_HIP_FLEX to 50f, J.R_KNEE to 90f, J.L_HIP_FLEX to -20f, J.L_KNEE to 30f, J.L_SH_FLEX to 45f, J.L_EL to 90f, J.R_SH_FLEX to -40f, J.R_EL to 90f, J.WAIST_FWD to 10f)),
            PoseDef(14, "跳", "Jumping", Ground.AIR, null,
                p(J.LIFT to 0.25f, J.R_SH_FLEX to 150f, J.R_SH_ABD to 20f, J.L_SH_FLEX to 150f, J.L_SH_ABD to 20f, J.R_HIP_FLEX to 30f, J.R_KNEE to 60f, J.R_ANK to 20f, J.L_HIP_FLEX to 30f, J.L_KNEE to 60f, J.L_ANK to 20f, J.WAIST_FWD to -5f)),
            PoseDef(15, "投掷", "Throwing", Ground.BOTH, null,
                p(J.R_SH_ABD to 110f, J.R_SH_FLEX to -30f, J.R_EL to 90f, J.L_SH_FLEX to 90f, J.L_HIP_FLEX to 50f, J.L_KNEE to 60f, J.R_HIP_FLEX to -15f, J.R_KNEE to 30f, J.WAIST_TWIST to -25f)),
            PoseDef(16, "踢", "Kicking", Ground.LEFT, null,
                p(J.R_HIP_FLEX to 70f, J.R_ANK to 20f, J.L_KNEE to 10f, J.L_SH_ABD to 60f, J.R_SH_ABD to 20f, J.WAIST_FWD to -10f)),
            PoseDef(17, "挥臂", "Swinging", Ground.BOTH, null,
                p(J.R_SH_FLEX to 90f, J.R_SH_YAW to 60f, J.R_EL to 30f, J.L_SH_FLEX to 90f, J.L_SH_YAW to 60f, J.L_EL to 30f, J.WAIST_TWIST to 35f, J.R_KNEE to 20f, J.L_KNEE to 20f, J.R_HIP_ABD to 10f, J.L_HIP_ABD to 10f)),
            PoseDef(18, "弯腰拾物", "Picking up", Ground.BOTH, null,
                p(J.WAIST_FWD to 80f, J.R_KNEE to 20f, J.L_KNEE to 20f, J.R_SH_FLEX to 160f, J.L_SH_FLEX to -30f, J.NECK_FWD to 10f)),
            PoseDef(19, "后仰失衡", "Falling back", Ground.BOTH, null,
                p(J.WAIST_FWD to -25f, J.R_SH_FLEX to 120f, J.R_SH_ABD to 30f, J.R_EL to 20f, J.L_SH_FLEX to 120f, J.L_SH_ABD to 30f, J.L_EL to 20f, J.R_HIP_FLEX to 30f, J.L_KNEE to 25f, J.NECK_FWD to -10f)),
            PoseDef(20, "伸懒腰", "Stretch", Ground.BOTH_TIPTOE, null,
                p(J.R_SH_FLEX to 160f, J.R_EL to 30f, J.L_SH_FLEX to 160f, J.L_EL to 30f, J.WAIST_FWD to -10f, J.WAIST_SIDE to 5f, J.NECK_FWD to -20f, J.R_HIP_FLEX to 35f, J.R_KNEE to 70f, J.R_ANK to 25f)),
        )

    val count: Int
        get() = all.size

    /** Angles after the limit clamp and the both-feet knee correction (computed once). */
    val resolved: List<FloatArray> by lazy { all.map { Mannequin.settle(it) } }
}

/** A plaster solid of the still-life sets (design `stillLifeSets`); sizes in metres, centre on the top face. */
sealed class Solid(val x: Float, val z: Float) {
    class Ball(x: Float, z: Float, val d: Float) : Solid(x, z)

    class Cube(x: Float, z: Float, val size: Float, val turnDeg: Float = 0f) : Solid(x, z)

    class Cylinder(x: Float, z: Float, val d: Float, val h: Float, val lying: Boolean = false, val turnDeg: Float = 0f) : Solid(x, z)

    class Cone(x: Float, z: Float, val d: Float, val h: Float) : Solid(x, z)

    class Block(x: Float, z: Float, val w: Float, val h: Float, val dz: Float, val y: Float = 0f, val turnDeg: Float = 0f) : Solid(x, z)

    class Torus(x: Float, z: Float, val outer: Float, val inner: Float, val turnDeg: Float = 0f) : Solid(x, z)
}

object StillLife {
    /** Five sets within 0.35 x 0.35 m of the top centre. */
    val sets: List<List<Solid>> =
        listOf(
            listOf(Solid.Ball(-0.09f, 0.05f, 0.15f), Solid.Cube(0.06f, -0.06f, 0.15f, 20f), Solid.Cylinder(0.08f, 0.09f, 0.10f, 0.20f)),
            listOf(Solid.Cone(-0.03f, -0.02f, 0.16f, 0.24f), Solid.Ball(0.10f, 0.07f, 0.12f)),
            listOf(Solid.Ball(-0.06f, -0.05f, 0.18f), Solid.Ball(0.09f, -0.02f, 0.12f), Solid.Ball(0.01f, 0.11f, 0.08f)),
            listOf(
                Solid.Block(-0.04f, -0.03f, 0.22f, 0.08f, 0.10f, 0f, 10f),
                Solid.Block(-0.08f, -0.03f, 0.10f, 0.22f, 0.08f, 0.08f, 10f),
                Solid.Cylinder(0.09f, 0.10f, 0.08f, 0.22f, lying = true, turnDeg = -30f),
            ),
            listOf(Solid.Ball(-0.10f, 0.06f, 0.14f), Solid.Torus(0.02f, -0.06f, 0.20f, 0.12f, 15f), Solid.Cube(0.11f, 0.09f, 0.12f, -15f)),
        )

    val count: Int
        get() = sets.size
}
