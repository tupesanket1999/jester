package io.github.tupesanket1999.jester.jester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JesterRigTest {
    private fun run(state: String, tool: String = "", seconds: Double = 5.0): List<String> {
        val rig = JesterRig(0.0)
        val sounds = ArrayList<String>()
        rig.onSound = { sounds.add(it) }
        rig.request(state, tool)
        val dt = 1.0 / 120
        var t = 0.0
        val n = (seconds / dt).toInt()
        for (i in 1..n) {
            t = i * dt
            rig.step(t, Pt(if (i % 200 < 5) 3f else 0f, 0f), if (i % 3 == 0) Pt(120f, 80f) else null, 200f, 200f)
            val f = rig.frame()
            assertTrue("NaN in frame for $state/$tool at t=$t", f.isFinite())
            assertTrue("NaN in pose for $state/$tool at t=$t", rig.pose.isFinite())
            rig.particles.forEach { q -> assertTrue(q.p.isFinite() && rig.particleItem(q).xf.isFinite()) }
            rig.tails.forEach { assertTrue(it.tip.isFinite() && it.mid.isFinite()) }
        }
        assertEquals(JesterRig.key(state, tool), rig.key)
        return sounds
    }

    @Test fun everyStateStaysFinite() {
        for (s in listOf("sleep", "idle", "thinking", "waiting", "remind", "done", "error", "bogus")) run(s)
        for (tool in listOf("Edit", "Read", "Bash", "Agent")) run("working", tool)
    }

    @Test fun idleTricksStayFinite() {
        run("idle", seconds = 60.0)
    }

    @Test fun doneFiresBoing() {
        val s = run("done", seconds = 3.0)
        assertTrue(s.toString(), "boing" in s)
        assertTrue("whoosh" in s && "tada" in s && "giggle" in s)
    }

    @Test fun errorFiresThud() {
        val s = run("error", seconds = 2.0)
        assertTrue(s.toString(), "thud" in s)
        assertTrue("slide" in s)
    }

    @Test fun keyMapping() {
        assertEquals("working:scribe", JesterRig.key("working", "Write"))
        assertEquals("working:read", JesterRig.key("working", "Grep"))
        assertEquals("working:fast", JesterRig.key("working", "Bash"))
        assertEquals("working:juggle", JesterRig.key("working", "Task"))
        assertEquals("idle", JesterRig.key("nonsense", ""))
    }
}
