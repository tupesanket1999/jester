package io.github.tupesanket1999.jester.jester

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class JesterAudioTest {
  // name -> (swift duration seconds, peak target)
  private val spec = mapOf(
    "jingle" to (0.60 to 0.6f), "tinkle" to (0.51 to 0.3f), "boing" to (0.60 to 0.6f),
    "whoosh" to (0.42 to 0.55f), "slide" to (0.60 to 0.45f), "thud" to (0.35 to 0.7f),
    "tada" to (1.08 to 0.6f), "flick" to (0.24 to 0.45f), "giggle" to (0.60 to 0.5f),
  )

  private fun check(name: String, s: FloatArray?, dur: Double, target: Float) {
    assertNotNull(name, s)
    val secs = s!!.size / 44100.0
    assertTrue("$name duration $secs vs $dur", abs(secs - dur) <= dur * 0.10)
    assertEquals("$name peak", target, s.maxOf { abs(it) }, 0.01f)
  }

  @Test fun effectsHaveExpectedDurationAndPeak() {
    for ((name, p) in spec) check(name, JesterSynth.render(name), p.first, p.second)
    assertEquals(spec.keys, JesterSynth.effectNames.toSet())
  }

  @Test fun unknownEffectIsNull() = assertNull(JesterSynth.render("nope"))

  @Test fun babbleIsDeterministicAndNormalised() {
    val a = JesterSynth.babble("Hello there, jester!")
    val b = JesterSynth.babble("Hello there, jester!")
    assertNotNull(a)
    assertTrue(a!!.contentEquals(b!!))
    assertEquals(0.4f, a.maxOf { abs(it) }, 0.01f)
    assertTrue(a.size > 0)
    assertTrue(!a.contentEquals(JesterSynth.babble("Something else entirely")!!))
    assertNull(JesterSynth.babble("123 ...!"))
  }

  @Test fun babbleLengthScalesWithLetters() {
    // 3 syllables min: 3 * (0.07 + 0.022) s
    val short = JesterSynth.babble("hi")!!
    assertEquals(3 * (0.07 + 0.022), short.size / 44100.0, 0.02)
    val long = JesterSynth.babble("x".repeat(200))!!
    assertEquals(22 * (0.07 + 0.022), long.size / 44100.0, 0.05)
  }
}
