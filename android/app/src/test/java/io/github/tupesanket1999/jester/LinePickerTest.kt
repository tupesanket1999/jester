package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.voice.LinePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LinePickerTest {
    private val pool = listOf("a", "b", "c")

    @Test fun neverRepeatsTwiceInARowOverManyPicks() {
        val p = LinePicker(Random(42))
        var prev = p.pick("done", pool)
        assertTrue(prev in pool)
        repeat(50) {
            val next = p.pick("done", pool)
            assertTrue(next in pool)
            assertNotEquals(prev, next)
            prev = next
        }
    }

    @Test fun poolsTrackLastLineIndependently() {
        val p = LinePicker(Random(7))
        val first = p.pick("x", pool)
        // a different pool has no memory of "x"'s last line, so it may return the same value
        val other = LinePicker(Random(7)).pick("y", pool)
        assertEquals(first, other)
        assertTrue(p.pick("x", pool) != first)
    }

    @Test fun singleLinePoolAlwaysReturnsIt() {
        val p = LinePicker(Random(1))
        repeat(5) { assertEquals("only", p.pick("solo", listOf("only"))) }
    }
}
