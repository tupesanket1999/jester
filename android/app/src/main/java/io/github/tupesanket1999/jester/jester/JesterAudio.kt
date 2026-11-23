// JesterAudio.kt: port of JesterAudio.swift. Procedural sound effects and "babble" for the jester, synthesised in code
// (no audio files). Synthesis lives in JesterSynth (no android imports, JVM-testable); JesterAudio plays it with
// AudioTrack and only holds tracks while a sound is playing, so it costs nothing idle.
package io.github.tupesanket1999.jester.jester

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** tiny deterministic PRNG (SplitMix64) so babble is stable per line */
class SeededRNG(seed: ULong) {
  private var s: ULong = seed
  fun next(): ULong {
    s += 0x9E3779B97F4A7C15uL
    var z = s
    z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
    z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
    return z xor (z shr 31)
  }
}

/** Pure synthesis, 44.1 kHz mono float. */
object JesterSynth {
  const val SR = 44100
  private const val sr = 44100f
  private const val PI_F = PI.toFloat()

  /** names of the fixed effects */
  val effectNames = listOf("jingle", "tinkle", "boing", "whoosh", "slide", "thud", "tada", "flick", "giggle")

  fun render(name: String, rnd: Random = Random.Default): FloatArray? = when (name) {
    "jingle" -> peak(jingle(5, 0.14f, 0.5f, rnd), 0.6f)
    "tinkle" -> peak(jingle(2, 0.05f, 0.25f, rnd), 0.3f)
    "boing" -> peak(boing(), 0.6f)
    "whoosh" -> peak(whoosh(0.42f, rnd), 0.55f)
    "slide" -> peak(slide(), 0.45f)
    "thud" -> peak(thud(rnd), 0.7f)
    "tada" -> peak(tada(rnd), 0.6f)
    "flick" -> peak(flick(rnd), 0.45f)
    "giggle" -> peak(giggle(), 0.5f)
    else -> null
  }

  /** Animal-Crossing-style babble: one formant "syllable" per few letters, seeded by the text. */
  fun babble(text: String): FloatArray? {
    var letters = 0
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      if (Character.isLetter(cp)) letters++
      i += Character.charCount(cp)
    }
    if (letters == 0) return null
    var seed = 14695981039346656037uL   // FNV-1a, stable across launches
    for (b in text.toByteArray(Charsets.UTF_8)) seed = (seed xor (b.toULong() and 0xFFuL)) * 1099511628211uL
    val rng = SeededRNG(seed)
    val vowels = arrayOf(730f to 1090f, 530f to 1840f, 300f to 2290f, 570f to 840f, 440f to 1020f)
    val parts = ArrayList<FloatArray>()
    for (k in 0 until min(22, max(3, letters / 3))) {
      val v = vowels[(rng.next() % vowels.size.toULong()).toInt()]
      val f0 = 420 + (rng.next() % 160uL).toFloat() - (if (k % 5 == 4) 60 else 0)
      parts += formant(f0, v.first, v.second, 0.07f, 0.06f)
      parts += FloatArray((0.022f * sr).toInt())
    }
    return peak(concat(parts), 0.4f)
  }

  /** scale to a target peak so effects sit at consistent loudness and never clip */
  fun peak(s: FloatArray, target: Float): FloatArray {
    var m = 0f
    for (x in s) m = max(m, abs(x))
    return if (m > 0) FloatArray(s.size) { s[it] * target / m } else s
  }

  private fun n(secs: Float) = (secs * sr).toInt()
  private fun concat(parts: List<FloatArray>): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) { p.copyInto(out, o); o += p.size }
    return out
  }

  private val bellPartials = arrayOf(floatArrayOf(1f, 1f, 7f), floatArrayOf(2.76f, 0.5f, 10f), floatArrayOf(5.4f, 0.25f, 15f), floatArrayOf(8.93f, 0.12f, 22f))
  private fun bell(f: Float, dur: Float, gain: Float): FloatArray = FloatArray(n(dur)) { i ->
    val t = i / sr
    val atk = min(1f, t / 0.002f)
    var sum = 0f
    for (p in bellPartials) sum += p[1] * exp(-t * p[2]) * sin(2 * PI_F * f * p[0] * t)
    atk * sum * gain * 0.45f
  }
  private fun mix(parts: List<Pair<Float, FloatArray>>): FloatArray {
    val len = parts.maxOfOrNull { n(it.first) + it.second.size } ?: 0
    val out = FloatArray(len)
    for ((at, s) in parts) { val o = n(at); for (i in s.indices) out[o + i] += s[i] }
    return out
  }
  private fun jingle(count: Int, spread: Float, gain: Float, rnd: Random): FloatArray =
    mix((0 until count).map { k ->
      (k * spread / max(1, count - 1) + rnd.nextFloat() * 0.015f) to bell(2300f + rnd.nextFloat() * 1300f, 0.45f, gain)
    })
  private fun boing(): FloatArray {
    var ph = 0f
    return FloatArray(n(0.6f)) { i ->
      val t = i / sr
      val f = (110 + 320 * exp(-7 * t)) * (1 + 0.18f * sin(2 * PI_F * 26 * t) * exp(-3 * t))
      ph += 2 * PI_F * f / sr
      sin(ph) * exp(-3.2f * t) * min(1f, t / 0.004f) * 0.7f
    }
  }
  private fun whoosh(d: Float, rnd: Random): FloatArray {
    var lp = 0f
    return FloatArray(n(d)) { i ->
      val t = i / sr
      val u = t / d
      val a = 0.02f + 0.25f * sin(PI_F * u)      // low-pass cutoff sweeps up then down
      lp += a * (rnd.nextFloat() * 2 - 1 - lp)
      lp * sin(PI_F * u) * 1.6f
    }
  }
  private fun slide(): FloatArray {
    var ph = 0f
    return FloatArray(n(0.6f)) { i ->
      val t = i / sr
      val u = t / 0.6f
      val f = (1600 - 1250 * u * u) * (1 + 0.012f * sin(2 * PI_F * 7 * t))
      ph += 2 * PI_F * f / sr
      (sin(ph) + 0.2f * sin(2 * ph)) * min(1f, t / 0.02f) * (1 - u * 0.6f) * 0.45f
    }
  }
  private fun thud(rnd: Random): FloatArray {
    var ph = 0f
    var lp = 0f
    return FloatArray(n(0.35f)) { i ->
      val t = i / sr
      ph += 2 * PI_F * (95 - 50 * t) / sr
      lp += 0.08f * (rnd.nextFloat() * 2 - 1 - lp)
      (sin(ph) * exp(-9 * t) * 0.9f + lp * exp(-25 * t) * 1.4f) * min(1f, t / 0.002f)
    }
  }
  private fun tada(rnd: Random): FloatArray {
    val notes = floatArrayOf(1046.5f, 1318.5f, 1568f, 2093f)   // C6 E6 G6 C7
    return mix(notes.mapIndexed { i, f -> (i * 0.06f) to bell(f, 0.9f, 0.55f) } + listOf(0.24f to jingle(4, 0.1f, 0.3f, rnd)))
  }
  private fun flick(rnd: Random): FloatArray {
    var ph = 0f
    val head = FloatArray(n(0.06f)) { i ->
      val t = i / sr
      ph += 2 * PI_F * (900 + 18000 * t) / sr
      sin(ph) * exp(-40 * t) * 0.5f
    }
    val tail = whoosh(0.18f, rnd).also { for (i in it.indices) it[i] *= 0.5f }
    return head + tail
  }
  /** voiced vowel: harmonics of f0 weighted by two formant peaks; pitch falls by `drop` over the syllable */
  private fun formant(f0: Float, f1: Float, f2: Float, dur: Float, drop: Float): FloatArray {
    val count = n(dur)
    var phase = 0f
    val nh = (5000 / f0).toInt()
    val amps = FloatArray(max(1, nh)) { idx ->
      val f = (idx + 1) * f0
      1 / (1 + ((f - f1) / 90).pow(2)) + 0.6f / (1 + ((f - f2) / 220).pow(2)) + 0.04f
    }
    return FloatArray(count) { i ->
      val t = i / sr
      val u = t / dur
      phase += 2 * PI_F * f0 * (1 - drop * u) * (1 + 0.01f * sin(2 * PI_F * 6 * t)) / sr
      var s = 0f
      for (k in amps.indices) s += amps[k] * sin((k + 1) * phase)
      val env = min(1f, t / 0.006f) * min(1f, (dur - t) / 0.025f)
      s * env * 0.16f
    }
  }
  private fun giggle(): FloatArray {
    val parts = ArrayList<FloatArray>()
    for ((k, f0) in floatArrayOf(720f, 700f, 660f, 610f).withIndex()) {
      parts += formant(f0, 300f, 2300f, if (k == 3) 0.13f else 0.085f, 0.12f)
      parts += FloatArray(n(0.055f))
    }
    return concat(parts)
  }
}

class JesterAudio {
  var volume: Float = 0.35f

  private val lock = Any()
  private var effects: Map<String, FloatArray>? = null   // fixed effects, synthesised lazily on the audio thread
  private val pcm = HashMap<String, ShortArray>()
  private val lastPlayed = HashMap<String, Long>()
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private class Active(val track: AudioTrack, val release: Runnable)
  private val active = ArrayList<Active>()
  private val maxActive = 4
  private val idleStopMs = 4000L

  /** minGapMs stops the same sound machine-gunning (e.g. bells on every swing of the hat) */
  fun play(name: String, gain: Float = 1f, minGapMs: Long = 120) {
    val now = SystemClock.elapsedRealtime()
    synchronized(lock) {
      val last = lastPlayed[name]
      if (last != null && now - last < minGapMs) return
      lastPlayed[name] = now
    }
    val vol = volume
    post {
      val s = ensureEffects()[name] ?: return@post
      start(pcmFor(name, s), gain, vol)
    }
  }

  /** Animal-Crossing-style babble: one formant "syllable" per few letters, seeded by the text so a line always sounds the same. */
  fun babble(text: String) {
    val vol = volume
    post {
      val s = babbleSamples(text) ?: return@post
      start(toPcm(s), 1f, vol)
    }
  }

  /** for tools/tests */
  internal fun rendered(name: String): FloatArray? = ensureEffects()[name]
  internal fun babbleSamples(text: String): FloatArray? = JesterSynth.babble(text)

  fun release() {
    val h: Handler?
    val t: HandlerThread?
    synchronized(lock) { h = handler; t = thread; handler = null; thread = null }
    h?.removeCallbacksAndMessages(null)
    // release on the audio thread's behalf here; tracks are not touched elsewhere once handler is gone
    synchronized(active) { active.forEach { runCatching { it.track.stop() }; it.track.release() }; active.clear() }
    t?.quitSafely()
  }

  // MARK: playback
  private fun post(block: () -> Unit) {
    val h = synchronized(lock) {
      handler ?: run {
        val t = HandlerThread("jester-audio").also { it.start() }
        thread = t
        Handler(t.looper).also { handler = it }
      }
    }
    h.removeCallbacks(idleStop)
    h.post { block(); scheduleIdleStop() }
  }
  private val idleStop = Runnable {
    if (synchronized(active) { active.isEmpty() }) release() else scheduleIdleStop()
  }
  private fun scheduleIdleStop() {
    val h = synchronized(lock) { handler } ?: return
    h.removeCallbacks(idleStop)
    h.postDelayed(idleStop, idleStopMs)
  }

  private fun ensureEffects(): Map<String, FloatArray> = synchronized(lock) {
    effects ?: JesterSynth.effectNames.associateWith { JesterSynth.render(it)!! }.also { effects = it }
  }
  private fun pcmFor(name: String, s: FloatArray): ShortArray = pcm.getOrPut(name) { toPcm(s) }
  private fun toPcm(s: FloatArray) = ShortArray(s.size) { (max(-1f, min(1f, s[it])) * 32767f).toInt().toShort() }

  private fun start(data: ShortArray, gain: Float, vol: Float) {
    val h = synchronized(lock) { handler } ?: return
    if (data.isEmpty()) return
    val attrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .setSampleRate(JesterSynth.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
    val track = try {
      AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
        .setBufferSizeInBytes(data.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
    } catch (e: Exception) { return }
    if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return }
    track.write(data, 0, data.size)
    track.setVolume((gain * vol).coerceIn(0f, 1f))
    // allow ~4 overlapping sounds: drop the oldest
    synchronized(active) {
      while (active.size >= maxActive) {
        val old = active.removeAt(0)
        h.removeCallbacks(old.release)
        runCatching { old.track.stop() }; old.track.release()
      }
    }
    lateinit var a: Active
    val rel = Runnable {
      synchronized(active) { active.remove(a) }
      runCatching { track.stop() }; track.release()
    }
    a = Active(track, rel)
    synchronized(active) { active.add(a) }
    track.play()
    h.postDelayed(rel, data.size * 1000L / JesterSynth.SR + 150)
  }
}
