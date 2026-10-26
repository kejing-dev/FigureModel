package io.embodit.figuremodel.domain

import io.embodit.figuremodel.domain.model.Ground
import io.embodit.figuremodel.domain.model.LightPreset
import io.embodit.figuremodel.domain.model.Mannequin
import io.embodit.figuremodel.domain.model.Part
import io.embodit.figuremodel.domain.model.Poses
import io.embodit.figuremodel.domain.model.Vec3
import io.embodit.figuremodel.domain.usecase.LightRule
import io.embodit.figuremodel.domain.usecase.PoseSequence
import io.embodit.figuremodel.domain.usecase.frontOf
import io.embodit.figuremodel.domain.usecase.yawFacing
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM checks of the pose table, the kinematics and the light / order rules. */
class FigureDomainTest {

    private fun soleY(f: io.embodit.figuremodel.domain.model.FigureFrame, foot: Part): Float {
        val p = f.parts[foot.ordinal]
        // foot box 0.09 x 0.08 x 0.25 about its centre: lowest of its 8 corners
        var low = Float.MAX_VALUE
        for (x in listOf(-0.045f, 0.045f)) for (y in listOf(-0.04f, 0.04f)) for (z in listOf(-0.125f, 0.125f)) {
            low = min(low, (p.position + p.rotation.rotate(Vec3(x, y, z))).y)
        }
        return low
    }

    @Test
    fun attentionIsLifeSize() {
        val f = Mannequin.solve(Poses.resolved[0])
        println("pose 1 head top ${f.headTop} chest ${f.chest} pelvisBottom ${f.pelvisBottom}")
        assertEquals(1.75f, f.headTop, 0.06f)
    }

    @Test
    fun feetOnTheFloor() {
        for ((i, def) in Poses.all.withIndex()) {
            val a = Poses.resolved[i]
            val f = Mannequin.solve(a)
            val r = soleY(f, Part.R_FOOT)
            val l = soleY(f, Part.L_FOOT)
            val low = min(r, l)
            println("  angles knee R=${a[io.embodit.figuremodel.domain.model.J.R_KNEE]} L=${a[io.embodit.figuremodel.domain.model.J.L_KNEE]} ank R=${a[io.embodit.figuremodel.domain.model.J.R_ANK]} L=${a[io.embodit.figuremodel.domain.model.J.L_ANK]}")
            println(
                "pose ${def.n} ${def.en}: soles R=%.3f L=%.3f head=%.2f pelvisBottom=%.2f kneeFix=%s".format(
                    r, l, f.headTop, f.pelvisBottom, Mannequin.kneeFixes[def.n]
                )
            )
            when (def.ground) {
                Ground.AIR -> assertTrue("pose ${def.n} lifts", low > 0.02f)
                Ground.BOTH -> {
                    assertEquals("pose ${def.n} lowest sole", 0f, low, 0.03f)
                    assertTrue("pose ${def.n} feet level |${r - l}|", abs(r - l) <= 0.03f)
                }
                Ground.BOTH_TIPTOE, Ground.LEFT -> assertEquals("pose ${def.n} lowest sole", 0f, low, 0.03f)
                else -> assertTrue("pose ${def.n} nothing below the floor", low > -0.03f)
            }
            if (def.ground == Ground.FLOOR) assertTrue("pose ${def.n} sits on the floor: ${f.pelvisBottom}", f.pelvisBottom < 0.15f)
            if (def.ground == Ground.SEAT) assertEquals("pose ${def.n} seat height", 0.44f, f.pelvisBottom, 0.05f)
            run {
            }
        }
    }

    @Test
    fun lightPresetsSitWhereTheViewerExpects() {
        // the figure faces +Z (towards the viewer standing at +Z)
        val front0 = frontOf(yawFacing(Vec3(0f, 0f, 1f)))
        val towardViewer = Vec3(0f, 0f, 1f)
        val (azL, elL) = LightRule.preset(LightPreset.LEFT, front0, towardViewer, 0f)
        val left = LightRule.direction(front0, azL, elL)
        println("left preset az=$azL dir=$left")
        assertTrue("viewer's left is -X", left.x < -0.3f)
        assertTrue("in front of the figure", left.z > 0.3f)
        assertEquals(-45f, azL, 0.5f)
        val (azB, _) = LightRule.preset(LightPreset.BACK, front0, towardViewer, 0f)
        assertTrue("back light behind the figure", LightRule.direction(front0, azB, 60f).z < -0.3f)
        // the orb is pulled in under 2.2 m
        val dir = LightRule.direction(front0, 0f, 80f)
        val at = LightRule.orbPosition(Vec3(0f, 1.3f, 0f), dir, 1.4f, 0f, null)
        assertTrue("orb at most 2.2 m high: ${at.y}", at.y <= 2.2001f)
    }

    @Test
    fun shuffleRoundsDoNotRepeat() {
        val seq = PoseSequence(20, Random(7))
        seq.start(shuffle = true)
        val seen = mutableSetOf(seq.current)
        repeat(19) { seen += seq.next() }
        assertEquals(20, seen.size)
        val last = seq.current
        assertTrue("a new round does not start with the last pose", seq.next() != last)
        assertEquals(1, seq.position)
        seq.setShuffle(false)
        val c = seq.current
        assertEquals((c + 1) % 20, seq.next())
    }
}
