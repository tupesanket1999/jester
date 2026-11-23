// JesterRig.kt: Kotlin port of Jester.swift (procedural vector jester: two-bone IK limbs, spring-physics hat tails,
// juggling, particles). The rig math (everything above JesterPainter) is pure Kotlin and runs on the JVM; only
// JesterPainter touches android.graphics. World space is y-up with the ground at y = 0, exactly like the Swift source;
// draw() flips the canvas once.
package io.github.tupesanket1999.jester.jester

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LongSparseArray
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val PIf = 3.1415927f

// MARK: colours (Crimson Nova), packed ARGB ints (no android.graphics.Color so the rig runs on the JVM)
private fun rgb(r: Int, g: Int, b: Int, a: Float = 1f): Int =
    ((a * 255f + 0.5f).toInt() shl 24) or (r shl 16) or (g shl 8) or b

internal object JC {
    val crimson = rgb(230, 57, 70); val red = rgb(255, 77, 94); val rose = rgb(232, 130, 140); val blush = rgb(242, 167, 160)
    val amber = rgb(224, 165, 92); val ink = rgb(18, 11, 13); val skin = rgb(253, 233, 221); val cream = rgb(255, 244, 236)
    val gold = rgb(246, 196, 92); val goldDark = rgb(170, 112, 38); val crimsonDark = rgb(150, 30, 44)
    val amberDark = rgb(176, 116, 50); val mouth = rgb(96, 16, 26); val wood = rgb(122, 74, 52); val white = rgb(255, 255, 255)
    val sweat = rgb(150, 205, 255)
    val confetti = intArrayOf(crimson, amber, rose, gold, red, blush)
}

// MARK: math
internal class Pt(@JvmField val x: Float, @JvmField val y: Float) {
    operator fun plus(b: Pt) = Pt(x + b.x, y + b.y)
    operator fun minus(b: Pt) = Pt(x - b.x, y - b.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun isFinite() = x.isFinite() && y.isFinite()
}
private fun P(x: Float, y: Float) = Pt(x, y)
private val ZERO = Pt(0f, 0f)
private fun len(p: Pt) = hypot(p.x, p.y)
private fun norm(p: Pt): Pt { val l = len(p); return if (l < 1e-6f) P(0f, 1f) else p * (1f / l) }
private fun mixf(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun mixp(a: Pt, b: Pt, t: Float) = P(mixf(a.x, b.x, t), mixf(a.y, b.y, t))
private fun clamp(v: Float, lo: Float = 0f, hi: Float = 1f) = min(hi, max(lo, v))
private fun smooth(t: Float): Float { val x = clamp(t); return x * x * (3 - 2 * x) }
private fun easeOut(t: Float): Float { val x = 1 - clamp(t); return 1 - x * x * x }
private fun easeIn(t: Float): Float { val x = clamp(t); return x * x * x }
/** progress of t through [a, b], clamped to 0..1 */
private fun seg(t: Float, a: Float, b: Float) = clamp((t - a) / (b - a))
private fun osc(t: Float, hz: Float, ph: Float = 0f) = sin(2 * PIf * hz * t + ph)
private fun fract(x: Float) = x - floor(x)
private fun bez(a: Pt, c: Pt, b: Pt, t: Float): Pt { val u = 1 - t; return a * (u * u) + c * (2 * u * t) + b * (t * t) }
private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
private fun rnd(a: Double, b: Double) = a + Random.nextDouble() * (b - a)

/** CGAffineTransform: x' = a x + c y + tx, y' = b x + d y + ty. Builders compose like CG (new op applies first, in local space). */
internal class Aff(@JvmField val a: Float, @JvmField val b: Float, @JvmField val c: Float, @JvmField val d: Float,
                   @JvmField val tx: Float, @JvmField val ty: Float) {
    /** this after inner (CG: inner.concatenating(this)) */
    private fun after(i: Aff) = Aff(
        a * i.a + c * i.b, b * i.a + d * i.b, a * i.c + c * i.d, b * i.c + d * i.d,
        a * i.tx + c * i.ty + tx, b * i.tx + d * i.ty + ty)
    fun translatedBy(x: Float, y: Float) = after(Aff(1f, 0f, 0f, 1f, x, y))
    fun rotated(r: Float): Aff { val cs = cos(r); val sn = sin(r); return after(Aff(cs, sn, -sn, cs, 0f, 0f)) }
    fun scaledBy(sx: Float, sy: Float) = after(Aff(sx, 0f, 0f, sy, 0f, 0f))
    fun apply(p: Pt) = Pt(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)
    fun isFinite() = a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() && tx.isFinite() && ty.isFinite()
    companion object { val identity = Aff(1f, 0f, 0f, 1f, 0f, 0f) }
}
private fun TR(p: Pt) = Aff(1f, 0f, 0f, 1f, p.x, p.y)
private fun Pt.applying(t: Aff) = t.apply(this)

/** Two-bone IK: returns (joint, end). bend = -1 bends to the left of the root->target line, +1 to the right. */
private fun ik(root: Pt, target: Pt, l1: Float, l2: Float, bend: Float): Pair<Pt, Pt> {
    val d = target - root
    val dist = clamp(len(d), abs(l1 - l2) + 0.01f, (l1 + l2) * 0.999f)
    val cosA = clamp((l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist), -1f, 1f)
    val ang = atan2(d.y, d.x) + bend * acos(cosA)
    val joint = root + P(cos(ang), sin(ang)) * l1
    return Pair(joint, root + norm(d) * dist)
}

// MARK: pose
/**
 * Everything that moves. Hands, props and balls live in torso space (origin = hip, rotated with lean);
 * feet live in ground space unless feetAttach pulls them into body space (jumps, flips).
 */
internal class JPose(
    @JvmField var root: Pt = ZERO, @JvmField var rot: Float = 0f, @JvmField var squash: Float = 1f,
    @JvmField var hipY: Float = 44f, @JvmField var lean: Float = 0f,
    @JvmField var headTilt: Float = 0f, @JvmField var headNod: Float = 0f,
    @JvmField var handL: Pt = Pt(-19f, 6f), @JvmField var handR: Pt = Pt(19f, 6f),
    @JvmField var footL: Pt = Pt(-11f, 0f), @JvmField var footR: Pt = Pt(11f, 0f), @JvmField var feetAttach: Float = 0f,
    @JvmField var eyeOpen: Float = 1f, @JvmField var happy: Float = 0f, @JvmField var dizzy: Float = 0f,
    @JvmField var mouthOpen: Float = 0.1f, @JvmField var smile: Float = 0.6f,
    @JvmField var brow: Float = 0f, @JvmField var tongue: Float = 0f, @JvmField var look: Pt = ZERO, @JvmField var followMouse: Float = 0f,
    @JvmField var marotte: Float = 0f, @JvmField var marotteAngle: Float = 0.12f, @JvmField var marotteGrip: Float = 0.3f,
    @JvmField var orb: Float = 0f, @JvmField var scroll: Float = 0f, @JvmField var scrollPos: Pt = Pt(-3f, 16f),
    @JvmField var writeProgress: Float = 4f,
    @JvmField var quill: Float = 0f, @JvmField var card: Float = 0f, @JvmField var cardFlip: Float = 0f,
    @JvmField var balls: Float = 0f, @JvmField var ball0: Pt = ZERO, @JvmField var ball1: Pt = ZERO, @JvmField var ball2: Pt = ZERO,
    @JvmField var alert: Float = 0f, @JvmField var thought: Float = 0f, @JvmField var sweat: Float = 0f,
    @JvmField var stars: Float = 0f, @JvmField var glow: Float = 0.45f, @JvmField var droop: Float = 0f,
) {
    fun copy() = JPose(root, rot, squash, hipY, lean, headTilt, headNod, handL, handR, footL, footR, feetAttach,
        eyeOpen, happy, dizzy, mouthOpen, smile, brow, tongue, look, followMouse, marotte, marotteAngle, marotteGrip,
        orb, scroll, scrollPos, writeProgress, quill, card, cardFlip, balls, ball0, ball1, ball2,
        alert, thought, sweat, stars, glow, droop)

    fun isFinite(): Boolean =
        rot.isFinite() && squash.isFinite() && hipY.isFinite() && lean.isFinite() && headTilt.isFinite() && headNod.isFinite() &&
            feetAttach.isFinite() && eyeOpen.isFinite() && happy.isFinite() && dizzy.isFinite() && mouthOpen.isFinite() &&
            smile.isFinite() && brow.isFinite() && tongue.isFinite() && followMouse.isFinite() && marotte.isFinite() &&
            marotteAngle.isFinite() && marotteGrip.isFinite() && orb.isFinite() && scroll.isFinite() && writeProgress.isFinite() &&
            quill.isFinite() && card.isFinite() && cardFlip.isFinite() && balls.isFinite() && alert.isFinite() && thought.isFinite() &&
            sweat.isFinite() && stars.isFinite() && glow.isFinite() && droop.isFinite() &&
            root.isFinite() && handL.isFinite() && handR.isFinite() && footL.isFinite() && footR.isFinite() && look.isFinite() &&
            scrollPos.isFinite() && ball0.isFinite() && ball1.isFinite() && ball2.isFinite()

    companion object {
        fun mix(a: JPose, b: JPose, t: Float): JPose {
            if (t <= 0f) return a
            if (t >= 1f) return b
            fun f(x: Float, y: Float) = mixf(x, y, t)
            fun p(x: Pt, y: Pt) = mixp(x, y, t)
            return JPose(
                p(a.root, b.root), f(a.rot, b.rot), f(a.squash, b.squash), f(a.hipY, b.hipY), f(a.lean, b.lean),
                f(a.headTilt, b.headTilt), f(a.headNod, b.headNod),
                p(a.handL, b.handL), p(a.handR, b.handR), p(a.footL, b.footL), p(a.footR, b.footR), f(a.feetAttach, b.feetAttach),
                f(a.eyeOpen, b.eyeOpen), f(a.happy, b.happy), f(a.dizzy, b.dizzy), f(a.mouthOpen, b.mouthOpen), f(a.smile, b.smile),
                f(a.brow, b.brow), f(a.tongue, b.tongue), p(a.look, b.look), f(a.followMouse, b.followMouse),
                f(a.marotte, b.marotte), f(a.marotteAngle, b.marotteAngle), f(a.marotteGrip, b.marotteGrip),
                f(a.orb, b.orb), f(a.scroll, b.scroll), p(a.scrollPos, b.scrollPos), f(a.writeProgress, b.writeProgress),
                f(a.quill, b.quill), f(a.card, b.card), f(a.cardFlip, b.cardFlip),
                f(a.balls, b.balls), p(a.ball0, b.ball0), p(a.ball1, b.ball1), p(a.ball2, b.ball2),
                f(a.alert, b.alert), f(a.thought, b.thought), f(a.sweat, b.sweat), f(a.stars, b.stars),
                f(a.glow, b.glow), f(a.droop, b.droop))
        }
    }
}

/** Plays phases back to back with a short cross-fade at each boundary so nothing pops. */
private fun timeline(t: Float, phases: List<Pair<Float, (Float) -> JPose>>, xf: Float = 0.09f): JPose {
    var i = 0
    while (i + 1 < phases.size && t >= phases[i + 1].first) i++
    val cur = phases[i].second(t)
    if (i == 0 || t - phases[i].first >= xf) return cur
    return JPose.mix(phases[i - 1].second(t), cur, smooth((t - phases[i].first) / xf))
}

// MARK: particles
internal enum class PKind { CONFETTI, SPARKLE, ZZZ, NOTE, PUFF, CARD, INK }

internal class Particle(
    @JvmField val kind: PKind, @JvmField var p: Pt, @JvmField var v: Pt, @JvmField var life: Float,
    @JvmField var rot: Float = 0f, @JvmField var spin: Float = 0f, @JvmField var size: Float, @JvmField var color: Int,
) { @JvmField var age = 0f }

internal class Tail(@JvmField val l1: Float, @JvmField val l2: Float, @JvmField val color: Int) {
    @JvmField var base = ZERO; @JvmField var mid = ZERO; @JvmField var tip = ZERO
    @JvmField var vMid = ZERO; @JvmField var vTip = ZERO
    @JvmField var lastJingle = 0.0
    @JvmField var ready = false
}

internal class Limb(val a: Pt, val j: Pt, val b: Pt, val color: Int, val w: Float, val l1: Float, val l2: Float)
internal class Item(val xf: Aff, val alpha: Float) { companion object { val none = Item(Aff.identity, 0f) } }
internal class Frame {
    val legs = ArrayList<Limb>(); val shoes = ArrayList<Aff>(); val arms = ArrayList<Limb>(); val sleeves = ArrayList<Aff>()
    val hands = ArrayList<Aff>(); val tailBells = ArrayList<Aff>()
    var squash: Aff = Aff.identity; var shadow = Item.none
    var marotte = Item.none; var orb = Item.none; var swirl = Item.none; var scroll = Item.none; var quill = Item.none; var card = Item.none
    var cardFront = true
    val balls = ArrayList<Item>(); val dots = ArrayList<Item>(); val stars = ArrayList<Item>()
    var alert = Item.none; var sweat = Item.none

    fun clear() {
        legs.clear(); shoes.clear(); arms.clear(); sleeves.clear(); hands.clear(); tailBells.clear()
        balls.clear(); dots.clear(); stars.clear()
    }

    private fun ok(i: Item) = i.xf.isFinite() && i.alpha.isFinite()
    private fun okAll(l: List<Aff>): Boolean { for (x in l) if (!x.isFinite()) return false; return true }
    private fun okItems(l: List<Item>): Boolean { for (x in l) if (!ok(x)) return false; return true }
    private fun okLimbs(l: List<Limb>): Boolean { for (x in l) if (!(x.a.isFinite() && x.j.isFinite() && x.b.isFinite())) return false; return true }

    fun isFinite(): Boolean =
        ok(shadow) && ok(marotte) && ok(orb) && ok(swirl) && ok(scroll) && ok(quill) && ok(card) && ok(alert) && ok(sweat) &&
            okItems(balls) && okItems(dots) && okItems(stars) &&
            okAll(shoes) && okAll(sleeves) && okAll(hands) && okAll(tailBells) && squash.isFinite() &&
            okLimbs(legs) && okLimbs(arms)
}

// MARK: rig
class JesterRig(startTime: Double = 0.0) {
    var key = "sleep"; private set
    private var keyStart = startTime
    private var prevKey = "sleep"; private var prevStart = startTime; private var blendStart = 0.0; private var blendDur = 0.0
    private var requested = "sleep"
    private val fired = HashSet<String>()
    private var trick = -1; private var trickStart = 0.0; private var nextTrick = startTime + 6
    private var blinkAt = startTime + 2; private var nextBlink = startTime + 3
    private val lastSpawn = HashMap<String, Double>()
    internal val particles = ArrayList<Particle>()
    internal val tails = arrayOf(Tail(13f, 15f, JC.amber), Tail(13f, 14f, JC.red), Tail(13f, 15f, JC.amber))
    internal var pose = JPose()
    private var faceW = Pt(0f, 98f)
    private var mouse: Pt? = null   // world space
    var zoom = 0.8f
    /** sound cue for a moment in the choreography ("whoosh", "boing", "tada", "giggle", "slide", "thud", "jingle", "tinkle", "flick") */
    var onSound: ((String) -> Unit)? = null
    internal var glowCenter = Pt(0f, 68f); internal var glowAlpha = 0.45f
    var accent: Int = JC.crimson

    // geometry caches filled by solve(), read by draw()
    internal var T = Aff.identity; internal var Tt = Aff.identity; internal var Th = Aff.identity
    internal var now = startTime

    private var painter: JesterPainter? = null

    /** true: draw baked bitmap sprites (fast, default). false: the original full-vector path (tests / offline). */
    var useSprites: Boolean = true

    private val featKey = IntArray(9)
    /** change signature of the face (Swift featuresKey): the face bitmap is re-rendered only when this changes */
    internal fun featuresChanged(last: IntArray): Boolean {
        val p = pose; var ch = false
        featKey[0] = Math.round(p.eyeOpen * 40f); featKey[1] = if (p.happy > 0.5f) 40 else 0
        featKey[2] = Math.round(p.mouthOpen * 40f); featKey[3] = Math.round(p.smile * 40f); featKey[4] = Math.round(p.brow * 40f)
        featKey[5] = if (p.tongue > 0.5f) 40 else 0; featKey[6] = Math.round(p.look.x * 40f); featKey[7] = Math.round(p.look.y * 40f)
        featKey[8] = if (p.dizzy > 0.5f) (now * 40).toInt() else 0
        for (i in 0 until 9) if (featKey[i] != last[i]) { ch = true; last[i] = featKey[i] }
        return ch
    }

    /** px between the view bottom and the ground line (Mac: 10 + 12 * zoom) */
    val groundPad: Float get() = 10f + 12f * zoom

    companion object {
        // MARK: state requests
        fun key(state: String, tool: String): String = when (state) {
            "working" -> when (tool) {
                "Edit", "Write", "MultiEdit", "NotebookEdit" -> "working:scribe"
                "Read", "Grep", "Glob", "LS", "WebFetch", "WebSearch", "ToolSearch" -> "working:read"
                "Bash", "BashOutput" -> "working:fast"
                else -> "working:juggle"
            }
            "idle", "thinking", "waiting", "done", "error", "remind", "sleep" -> state
            else -> "idle"
        }
        private val URGENT = setOf("waiting", "error", "done", "remind")
        private val HIGH_FPS = setOf("waiting", "done", "error")
    }

    /** true while something fast is on screen (juggling, flips, hops, tricks, cross-fades): 120 Hz then, 60 Hz otherwise. */
    val wantsHighFPS: Boolean
        get() = key.startsWith("working") || key in HIGH_FPS || (key == "idle" && trick >= 0) ||
            now - blendStart < blendDur || particles.size > 8

    fun request(state: String, tool: String) { requested = key(state, tool) }

    private fun applyRequest() {
        if (requested == key) return
        val urgent = requested in URGENT
        val shown = now - keyStart
        val bothWork = key.startsWith("working") && requested.startsWith("working")
        // tools flip every second during a run; hold a juggling/scribing variant long enough to read
        if (!urgent && shown < (if (bothWork) 2.5 else 0.6)) return
        prevKey = key; prevStart = keyStart
        key = requested; keyStart = now; blendStart = now
        blendDur = if (prevKey == "error") 0.75 else if (key == "done") 0.18 else if (key == "sleep" || prevKey == "sleep") 0.9 else 0.32
        fired.clear()
        if (key == "idle") { trick = -1; nextTrick = now + rnd(5.0, 10.0) }
    }

    // MARK: per-frame update
    /**
     * windowDelta: how far the panel moved since the last frame (px, y-up) so the hat swings while dragged.
     * mouseLocal: cursor in view px, origin bottom-left (y-up), or null.
     */
    internal fun step(time: Double, windowDelta: Pt, mouseLocal: Pt?, viewW: Float, viewH: Float) {
        val dt = min(1f / 20f, max(1f / 240f, (time - now).toFloat()))
        now = time
        applyRequest()
        mouse = if (mouseLocal != null) toWorld(mouseLocal, viewW) else null

        // idle tricks
        if (key == "idle") {
            if (trick >= 0 && now - trickStart > trickLength(trick)) { trick = -1; nextTrick = now + rnd(8.0, 18.0) }
            if (trick < 0 && now > nextTrick && now - keyStart > 1.5) { trick = Random.nextInt(0, 6); trickStart = now; fired.remove("trick") }
        }
        // blinks
        if (now > nextBlink) {
            blinkAt = now
            nextBlink = now + (if (Random.nextDouble() < 0.2) 0.28 else rnd(2.2, 5.5))
        }

        var p = poseFor(key, (now - keyStart).toFloat())
        val bw = if (blendDur > 0) ((now - blendStart) / blendDur).toFloat() else 1f
        if (bw < 1f) p = JPose.mix(poseFor(prevKey, (now - prevStart).toFloat()), p, smooth(bw))
        // blink (not while eyes are already shut or smiling)
        val bk = (now - blinkAt).toFloat()
        if (bk < 0.14f && p.happy < 0.5f && p.dizzy < 0.5f) p.eyeOpen *= 1 - sin(PIf * bk / 0.14f)
        // eyes (and a touch of head) follow the cursor
        val m = mouse
        if (p.followMouse > 0f && m != null) {
            val d = m - faceW; val l = len(d)
            val target = norm(d) * min(1f, l / 140f)
            p.look = mixp(p.look, target, p.followMouse)
            p.headTilt += -0.07f * p.followMouse * clamp(d.x / 300f, -1f, 1f)
        }
        pose = p
        solve()
        events((now - keyStart).toFloat())
        simulateTails(dt, windowDelta * (1f / zoom))
        simulateParticles(dt)
    }

    private fun toWorld(local: Pt, viewW: Float) = P((local.x - viewW / 2) / zoom, (local.y - groundPad) / zoom)

    private fun solve() {
        val p = pose
        T = Aff.identity.translatedBy(p.root.x, p.root.y).translatedBy(0f, 52f).rotated(p.rot).translatedBy(0f, -52f)
        Tt = T.translatedBy(0f, p.hipY).rotated(p.lean)
        Th = Tt.translatedBy(0f, 37f).rotated(p.headTilt).translatedBy(0f, 17f + p.headNod)
        faceW = ZERO.applying(Th)
        glowCenter = P(0f, 68f).applying(T); glowAlpha = clamp(p.glow)
    }

    // MARK: choreography
    private fun trickLength(i: Int): Double = doubleArrayOf(1.5, 2.6, 2.5, 3.4, 2.3, 2.6)[max(0, min(5, i))]

    private fun standing(t: Float): JPose {
        val p = JPose()
        p.squash = 1 + 0.014f * osc(t, 0.3f)
        p.root = P(2 * osc(t, 0.12f), 0f); p.lean = -0.04f * osc(t, 0.12f); p.headTilt = 0.06f * osc(t, 0.12f, 0.8f)
        p.handL = P(-19f, 6 + 1.2f * osc(t, 0.3f, 0.4f)); p.handR = P(18f, 8 + 1.2f * osc(t, 0.3f, 0.9f))
        p.marotte = 1f; p.marotteAngle = 0.12f + 0.05f * osc(t, 0.25f); p.marotteGrip = 0.3f
        p.followMouse = 0.85f
        return p
    }

    private fun poseFor(k: String, t: Float): JPose = when (k) {
        "idle" -> idle(t)
        "thinking" -> thinking(t)
        "working:juggle" -> juggling(t, 0.27f, 92f)
        "working:fast" -> juggling(t, 0.2f, 78f)
        "working:scribe" -> scribe(t)
        "working:read" -> reading(t)
        "waiting" -> waiting(t)
        "remind" -> remind(t)
        "done" -> done(t)
        "error" -> error(t)
        else -> sleep(t)
    }

    private fun idle(t: Float): JPose {
        val base = standing(t)
        if (key != "idle" || trick < 0) return base
        val k = (now - trickStart).toFloat(); val L = trickLength(trick).toFloat()
        val w = smooth(k / 0.3f) * smooth((L - k) / 0.3f)
        var p = base.copy()
        when (trick) {
            0 -> { // hat jingle: shake the head so the bells ring
                val env = sin(PIf * clamp(k / L))
                p.headTilt += 0.26f * osc(k, 5.5f) * env; p.root = P(p.root.x, p.root.y + 2 * abs(osc(k, 2.75f)) * env)
                p.happy = 1f; p.smile = 1f; p.mouthOpen = 0.45f; p.followMouse = 0f
            }
            1 -> { // shuffle dance
                val b = osc(k, 2f)
                p.root = P(p.root.x + 4 * osc(k, 1f), 3.5f * abs(b))
                p.footL = P(-11 + 2 * osc(k, 1f), max(0f, 7 * b)); p.footR = P(11 + 2 * osc(k, 1f), max(0f, -7 * b))
                p.handL = P(-21f, 16 + 9 * b); p.handR = P(21f, 16 - 9 * b); p.marotteAngle = 0.4f * b
                p.lean = 0.08f * osc(k, 1f); p.headTilt = -0.12f * osc(k, 2f)
                p.happy = 1f; p.smile = 1f; p.mouthOpen = 0.35f; p.followMouse = 0f
            }
            2 -> { // card flourish with the left hand
                val up = smooth(seg(k, 0f, 0.5f)); val flick = seg(k, 1.35f, 1.6f)
                p.handL = mixp(base.handL, P(-11f, 32f), up)
                if (flick > 0f) p.handL = mixp(P(-11f, 32f), P(-25f, 42f), easeOut(flick))
                if (k > 1.6f) p.handL = mixp(P(-25f, 42f), base.handL, smooth(seg(k, 1.6f, 2.3f)))
                p.card = if (k < 1.45f) up else 0f; p.cardFlip = k * 9
                p.look = P(-0.6f, 0.1f); p.followMouse = 0f; p.brow = 0.5f; p.smile = 0.9f; p.headTilt = 0.1f
            }
            3 -> { p = juggling(k, 0.26f, 84f); p.marotte = 0f } // quick juggle
            4 -> { // shade the eyes and peek at the cursor
                var dir = 1f
                mouse?.let { dir = if (it.x >= 0) 1f else -1f }
                p.root = P(p.root.x + 4 * dir, p.root.y); p.lean = -0.13f * dir; p.headTilt = -0.1f * dir
                p.handL = P(-10f, 54f); p.brow = 0.8f; p.followMouse = 1f; p.smile = 0.4f; p.mouthOpen = 0.2f
            }
            else -> { // big yawn and stretch
                val s = sin(PIf * clamp(k / L))
                p.handL = P(-11f, 60f); p.handR = P(11f, 60f); p.marotte = 0f
                p.squash = 1 + 0.07f * s; p.eyeOpen = 0.15f; p.mouthOpen = 0.9f; p.smile = 0.1f; p.followMouse = 0f
                p.headTilt = 0.1f * osc(k, 0.5f)
            }
        }
        return JPose.mix(base, p, w)
    }

    private fun thinking(t: Float): JPose {
        val p = standing(t)
        p.marotte = 0f; p.followMouse = 0f
        p.lean = 0.05f; p.headTilt = 0.12f + 0.05f * osc(t, 0.25f)
        p.handL = P(-14f, 22 + 1.5f * osc(t, 0.5f)); p.orb = 1f
        p.handR = P(6 + 1.2f * osc(t, 1.3f), 40f); p.headNod = -0.5f
        val g = smooth(0.5f + 0.5f * sin(t * 2 * PIf / 6) * 3)   // alternate: gaze at the orb, then up
        p.look = mixp(P(-0.65f, -0.55f), P(0.55f, 0.8f), g)
        p.brow = 0.55f; p.smile = 0.05f; p.mouthOpen = 0.04f; p.thought = 1f; p.glow = 0.55f
        return p
    }

    /** Three-ball cascade. Each ball: flight 2.1 beats, dwell 0.9 beats in the catching hand; hands alternate throws. */
    private fun juggling(t: Float, beat: Float, height: Float): JPose {
        val p = standing(t)
        p.marotte = 0f; p.balls = 1f; p.followMouse = 0f
        val T = t / beat; val D = 0.9f; val F = 3 - D
        val throwPt = arrayOf(P(-8f, 15f), P(8f, 15f)); val catchPt = arrayOf(P(-27f, 21f), P(27f, 21f)); val scoop = arrayOf(P(-17f, 8f), P(17f, 8f))
        val held = arrayOfNulls<Pt>(2)
        val balls = ArrayList<Pt>(3)
        for (i in 0 until 3) {
            val u = (T - i) % 3; val uu = if (u < 0) u + 3 else u
            val throwBeat = floor(T - uu + 0.0001f).toInt()
            val h = ((throwBeat % 2) + 2) % 2; val o = 1 - h
            if (uu < F) {
                val s = uu / F
                val a = throwPt[h]; val b = catchPt[o]
                balls.add(P(mixf(a.x, b.x, s), mixf(a.y, b.y, s) + 4 * height * s * (1 - s)))
            } else {
                val s = (uu - F) / D
                val b = bez(catchPt[o], scoop[o], throwPt[o], s)
                balls.add(b); held[o] = b
            }
        }
        for (h in 0 until 2) {
            val hb = held[h]
            if (hb != null) { val hp = hb - P(0f, 4f); if (h == 0) p.handL = hp else p.handR = hp; continue }
            val parity = h
            var last = floor(T)
            if (last.toInt() % 2 != parity) last -= 1
            val s = clamp((T - last) / (2 - D))
            val hp = bez(throwPt[h], (throwPt[h] + catchPt[h]) * 0.5f + P(0f, 9f), catchPt[h], smooth(s)) - P(0f, 4f)
            if (h == 0) p.handL = hp else p.handR = hp
        }
        p.ball0 = balls[0]; p.ball1 = balls[1]; p.ball2 = balls[2]
        var top = balls[0]
        for (b in balls) if (b.y > top.y) top = b
        p.look = norm(top - P(0f, 54f)) * 0.9f
        p.headTilt = -0.004f * top.x; p.root = P(p.root.x, 1.2f * abs(sin(PIf * T))); p.lean = 0.03f * osc(t, 0.4f)
        p.tongue = 1f; p.smile = 0.35f; p.brow = 0.3f; p.mouthOpen = 0.08f
        return p
    }

    private fun scribe(t: Float): JPose {
        val p = standing(t)
        p.marotte = 0f; p.followMouse = 0f; p.scroll = 1f; p.quill = 1f
        p.scrollPos = P(-3f, 15f); p.handL = P(-17f, 14f)
        val cyc = 1.05f; val lines = t / cyc; val line = floor(lines) % 5; val s = fract(lines)
        p.writeProgress = line + s
        p.handR = P(-13 + 20 * s, 23 - 4 * min(line, 3f) + 1.3f * osc(t, 7f)) + P(3f, 2f)
        p.look = P(-0.1f + 0.5f * s, -0.85f); p.headTilt = -0.1f; p.headNod = -1.5f
        p.tongue = 1f; p.brow = 0.25f; p.smile = 0.3f
        return p
    }

    private fun reading(t: Float): JPose {
        val p = standing(t)
        p.marotte = 0f; p.followMouse = 0f; p.scroll = 1f; p.writeProgress = 4f
        p.scrollPos = P(0f, 22f); p.handL = P(-17f, 22f); p.handR = P(17f, 22f)
        val lines = t / 0.9f; val s = fract(lines); val line = floor(lines) % 4
        p.look = P(-0.7f + 1.4f * smooth(s), -0.25f - 0.18f * line); p.headTilt = 0.05f * (s - 0.5f)
        p.brow = 0.4f; p.smile = 0.15f; p.mouthOpen = 0.12f
        return p
    }

    private fun hop(t: Float, height: Float, p: JPose) {
        // anticipation squash -> airborne stretch -> landing squash
        if (t < 0.14f) { val c = sin(PIf * t / 0.14f); p.hipY -= 6 * c; p.squash -= 0.12f * c }
        else if (t < 0.5f) {
            val s = (t - 0.14f) / 0.36f
            p.root = P(p.root.x, p.root.y + height * 4 * s * (1 - s)); p.squash += 0.07f * (1 - s); p.feetAttach = 0.85f * sin(PIf * s)
            p.footL = P(-9f, 3f); p.footR = P(9f, 3f)
        } else if (t < 0.62f) { val c = sin(PIf * (t - 0.5f) / 0.12f); p.hipY -= 5 * c; p.squash -= 0.13f * c }
    }

    private fun waiting(t: Float): JPose {
        val p = standing(t)
        val period = if (t < 6) 0.9f else 2.2f
        hop(t % period, if (t < 6) 20f else 14f, p)
        p.handR = P(14 + 8 * osc(t, 2.2f), 60f); p.marotteAngle = 0.4f * osc(t, 2.2f, 0.6f); p.marotteGrip = 0.22f
        p.handL = P(-21f, 32 + 7 * osc(t, 2.2f, PIf))
        p.eyeOpen = 1.15f; p.brow = 0.8f; p.mouthOpen = 0.55f; p.smile = 0.2f; p.followMouse = 1f; p.alert = 1f
        p.glow = 0.5f + 0.4f * abs(sin(3 * t))
        return p
    }

    private fun remind(t: Float): JPose {
        val p = standing(t)
        p.marotte = 0f
        hop(t % 2.6f, 9f, p)
        p.handR = P(20 + 6 * osc(t, 1.6f), 54f); p.handL = P(-17f, 13f)
        p.happy = if (t < 1.2f) 1f else 0f; p.smile = 0.95f; p.mouthOpen = 0.35f; p.followMouse = 1f; p.brow = 0.4f
        p.glow = 0.5f + 0.3f * abs(sin(2.2f * t))
        return p
    }

    private fun done(t: Float): JPose {
        val base = standing(t)
        return timeline(t, listOf<Pair<Float, (Float) -> JPose>>(
            0f to { _ -> val p = base.copy(); p.marotte = 0f
                p.hipY = 34f; p.squash = 0.82f; p.handL = P(-21f, -3f); p.handR = P(21f, -3f); p.brow = 0.5f; p.smile = 1f; p.mouthOpen = 0.3f
                p.followMouse = 0f; p },
            0.22f to { t -> val p = base.copy(); p.marotte = 0f
                val s = seg(t, 0.22f, 0.86f)
                p.root = P(p.root.x, 48 * 4 * s * (1 - s)); p.rot = 2 * PIf * smooth(s); p.squash = if (s < 0.15f) 1.12f else 1f
                p.feetAttach = 1f; val tuck = sin(PIf * clamp(s * 1.15f))
                p.footL = mixp(P(-11f, 0f), P(-7f, 24f), tuck); p.footR = mixp(P(11f, 0f), P(7f, 24f), tuck)
                p.handL = mixp(P(-24f, 40f), P(-11f, -2f), tuck); p.handR = mixp(P(24f, 40f), P(11f, -2f), tuck)
                p.happy = 1f; p.mouthOpen = 0.6f; p.smile = 1f; p.followMouse = 0f; p },
            0.86f to { t -> val p = base.copy(); p.marotte = 0f
                val l = seg(t, 0.86f, 1.12f)
                p.squash = 1 - 0.22f * sin(PIf * l); p.hipY = 44 - 9 * sin(PIf * l)
                p.footL = P(-14f, 0f); p.footR = P(14f, 0f); p.handL = P(-27f, 22f); p.handR = P(27f, 22f)
                p.happy = 1f; p.smile = 1f; p.mouthOpen = 0.5f; p.followMouse = 0f; p },
            1.12f to { t -> val p = base.copy(); p.marotte = 0f
                p.footL = P(-14f, 0f); p.footR = P(14f, 0f); p.handL = P(-24f, 58f); p.handR = P(24f, 58f)
                p.root = P(p.root.x, 2 * sin(PIf * seg(t, 1.12f, 1.9f)))
                p.happy = 1f; p.smile = 1f; p.mouthOpen = 0.7f; p.followMouse = 0f; p.glow = 0.8f; p },
            1.9f to { t -> val p = base.copy(); p.marotte = 0f
                val s = seg(t, 1.9f, 3.1f); val b = sin(PIf * s)
                p.hipY = 44 - 6 * b; p.headNod = -8 * b; p.headTilt = 0.18f * b; p.squash = 1 - 0.06f * b
                p.handR = bez(P(22f, 52f), P(14f, 30f), P(2f, 12f), smooth(seg(t, 1.9f, 2.4f)))
                if (s > 0.6f) p.handR = mixp(P(2f, 12f), P(20f, 8f), smooth((s - 0.6f) / 0.4f))
                p.handL = P(-29f, 20f); p.eyeOpen = 0f; p.happy = 1f; p.smile = 1f; p.mouthOpen = 0.15f; p.followMouse = 0f; p },
            3.1f to { t -> val p = base.copy()
                val u = t - 3.1f; val amp = exp(-u / 4)
                hop(u % 0.75f, 10 * amp, p)
                p.handL = mixp(base.handL, P(-21f, 30 + 24 * max(0f, osc(u, 0.7f))), amp)
                p.handR = mixp(base.handR, P(21f, 30 + 24 * max(0f, -osc(u, 0.7f))), amp)
                p.marotte = 1 - amp
                p.happy = if (u < 8) 1f else 0f; p.smile = 1f; p.mouthOpen = 0.2f + 0.4f * amp; p.glow = 0.45f + 0.3f * amp; p },
        ), 0.07f)
    }

    private fun error(t: Float): JPose {
        val base = standing(t)
        fun sitting(t: Float): JPose {
            val p = base.copy(); p.marotte = 0f; p.followMouse = 0f
            p.hipY = 15f; p.root = P(0f, p.root.y); p.footL = P(-25f, 0f); p.footR = P(25f, 0f)
            p.lean = 0.09f * osc(t, 0.7f); p.headTilt = 0.16f * osc(t, 0.9f, 1f)
            p.handL = P(-25f, -2f); p.handR = P(25f, -2f)
            p.dizzy = 1f; p.stars = 1f; p.sweat = 1f; p.smile = -0.35f; p.mouthOpen = 0.2f; p.brow = -0.6f
            return p
        }
        return timeline(t, listOf<Pair<Float, (Float) -> JPose>>(
            0f to { _ -> val p = base.copy(); p.marotte = 0f
                p.handL = P(-24f, 48f); p.handR = P(24f, 48f); p.brow = -0.8f; p.eyeOpen = 1.2f; p.mouthOpen = 0.75f; p.smile = -0.5f
                p.squash = 1.05f; p.followMouse = 0f; p },
            0.18f to { t -> val p = base.copy(); p.marotte = 0f
                val s = seg(t, 0.18f, 0.56f)
                p.root = P(p.root.x, 16 * sin(PIf * s)); p.rot = 0.4f * s; p.hipY = mixf(44f, 22f, easeIn(s))
                p.feetAttach = 1f; p.footL = P(-10f, mixf(0f, 34f, sin(PIf * s))); p.footR = P(16f, mixf(0f, 40f, sin(PIf * s)))
                val a = 2 * PIf * 3 * s
                p.handL = P(-18 + 9 * cos(a), 34 + 9 * sin(a)); p.handR = P(18 - 9 * cos(a), 34 - 9 * sin(a))
                p.eyeOpen = 1.25f; p.mouthOpen = 0.9f; p.smile = -0.6f; p.brow = -1f; p.followMouse = 0f; p },
            0.56f to { t -> val p = sitting(t); p.dizzy = 0f; p.stars = 0f
                val l = seg(t, 0.56f, 0.78f); p.squash = 1 - 0.24f * sin(PIf * l); p.eyeOpen = 0.2f; p.mouthOpen = 0.5f; p },
            0.78f to { t -> sitting(t) },
        ), 0.08f)
    }

    private fun sleep(t: Float): JPose {
        val p = JPose()
        p.hipY = 41f; p.lean = -0.08f; p.root = P(-2f, 0f); p.squash = 1 + 0.02f * osc(t, 0.22f)
        p.headTilt = -0.32f + 0.03f * osc(t, 0.22f); p.headNod = -3f
        p.eyeOpen = 0f; p.mouthOpen = 0.18f + 0.1f * osc(t, 0.22f); p.smile = 0.1f
        p.handR = P(17f, 10f); p.marotte = 1f; p.marotteAngle = 0.04f; p.marotteGrip = 0.95f
        p.handL = P(-18f, 3f); p.droop = 1f; p.glow = 0.15f
        return p
    }

    // MARK: one-shot effects
    private inline fun once(id: String, at: Float, t: Float, f: () -> Unit) {
        if (t >= at && fired.add(id)) f()
    }
    private inline fun every(id: String, secs: Double, f: () -> Unit) {
        if (now - (lastSpawn[id] ?: 0.0) >= secs) { lastSpawn[id] = now; f() }
    }
    private fun snd(name: String) { onSound?.invoke(name) }

    private fun events(t: Float) {
        val head = P(0f, 20f).applying(Th)
        when (key) {
            "done" -> {
                once("takeoff", 0.2f, t) { snd("whoosh") }
                once("giggle", 1.35f, t) { snd("giggle") }
                once("land", 0.86f, t) {
                    snd("boing")
                    for (n in 0 until 44) {
                        val a = rnd(0.25f * PIf, 0.75f * PIf); val sp = rnd(130f, 330f)
                        particles.add(Particle(PKind.CONFETTI, P(rnd(-12f, 12f), 30f), P(cos(a), sin(a)) * sp,
                            life = rnd(1.4f, 2.4f), rot = rnd(0f, 6f), spin = rnd(-14f, 14f),
                            size = rnd(3f, 5.5f), color = JC.confetti[Random.nextInt(JC.confetti.size)]))
                    }
                    puff(P(-14f, 2f)); puff(P(14f, 2f))
                }
                once("tada", 1.15f, t) {
                    snd("tada"); for (n in 0 until 6) sparkle(P(0f, 70f) + P(rnd(-40f, 40f), rnd(-10f, 40f)))
                }
                if (t > 3 && t < 9) every("cele", 0.5) { sparkle(P(rnd(-45f, 45f), rnd(40f, 120f))) }
            }
            "error" -> {
                once("slip", 0.2f, t) { snd("slide") }
                once("thud", 0.58f, t) { snd("thud"); puff(P(-10f, 3f)); puff(P(10f, 3f)); puff(P(0f, 2f)) }
            }
            "sleep" -> every("z", 1.7) {
                particles.add(Particle(PKind.ZZZ, head + P(10f, 6f), P(14f, 18f), life = 2.6f, rot = 0.2f, spin = 0.1f, size = 9f, color = JC.rose))
            }
            "waiting" -> once("ask", 0.05f, t) { snd("tinkle") }
            "remind" -> {
                once("hello", 0.05f, t) { snd("jingle") }
                every("note", 1.3) { note(head + P(-18f, 4f)) }
            }
            "thinking" -> every("orb", 0.45) {
                sparkle(pose.handL.applying(Tt) + P(rnd(-10f, 10f), rnd(8f, 24f)), small = true)
            }
            "working:scribe" -> every("ink", 0.35) {
                particles.add(Particle(PKind.INK, pose.handR.applying(Tt), P(rnd(-20f, 20f), rnd(10f, 40f)),
                    life = 0.5f, size = 1.6f, color = JC.ink))
            }
            "idle" -> if (trick >= 0) {
                val k = (now - trickStart).toFloat()
                if (trick == 0) once("trick", 0.1f, k) { snd("jingle"); note(head + P(-16f, 6f)); note(head + P(16f, 10f)) }
                if (trick == 2) {
                    once("trick", 1.45f, k) {
                        snd("flick")
                        particles.add(Particle(PKind.CARD, pose.handL.applying(Tt), P(-120f, 230f), life = 1.1f, rot = 0f, spin = 14f,
                            size = 7f, color = JC.white))
                    }
                }
                if (trick == 5) once("trick", 0.8f, k) { sparkle(head + P(0f, 10f), small = true) }
            }
            else -> {}
        }
    }
    private fun puff(at: Pt) {
        for (n in 0 until 4) {
            particles.add(Particle(PKind.PUFF, at, P(rnd(-40f, 40f), rnd(6f, 30f)), life = 0.6f,
                size = rnd(4f, 7f), color = rgb(235, 220, 215, 0.9f)))
        }
    }
    private fun sparkle(at: Pt, small: Boolean = false) {
        val cols = intArrayOf(JC.gold, JC.blush, JC.white)
        particles.add(Particle(PKind.SPARKLE, at, P(0f, 10f), life = if (small) 0.6f else 0.9f, rot = rnd(0f, 1f), spin = 2f,
            size = if (small) 3.5f else 6f, color = cols[Random.nextInt(3)]))
    }
    private fun note(at: Pt) {
        val cols = intArrayOf(JC.crimson, JC.amber, JC.rose)
        particles.add(Particle(PKind.NOTE, at, P(rnd(-12f, 12f), 34f), life = 1.6f, rot = rnd(-0.3f, 0.3f),
            size = 11f, color = cols[Random.nextInt(3)]))
    }

    // MARK: physics
    private fun simulateTails(dt: Float, windowDelta: Pt) {
        val d = pose.droop
        // base (head space) and rest offsets for mid and tip; droop bends them down for sleep
        val restBase = arrayOf(P(-9f, 16f), P(0f, 19f), P(9f, 16f))
        val restMid = arrayOf(mixp(P(-13f, 8f), P(-11f, -2f), d), mixp(P(1f, 13f), P(4f, 8f), d), mixp(P(13f, 8f), P(11f, -2f), d))
        val restTip = arrayOf(mixp(P(-25f, 0f), P(-17f, -14f), d), mixp(P(7f, 25f), P(15f, 4f), d), mixp(P(25f, 0f), P(17f, -14f), d))
        val steps = max(1, ceil(dt / (1f / 240f)).toInt())
        val h = dt / steps
        for (i in 0 until 3) {
            val tl = tails[i]
            val base = restBase[i].applying(Th)
            val mT = (restBase[i] + restMid[i]).applying(Th); val tT = (restBase[i] + restTip[i]).applying(Th)
            if (!tl.ready) { tl.mid = mT; tl.tip = tT; tl.ready = true }
            tl.base = base
            tl.mid = tl.mid - windowDelta; tl.tip = tl.tip - windowDelta   // inertia when the window is dragged
            for (n in 0 until steps) {
                val g = P(0f, -70f)
                val aM = (mT - tl.mid) * 240f - tl.vMid * 13f + g
                val aT = (tT - tl.tip) * 120f - tl.vTip * 8f + g
                tl.vMid = tl.vMid + aM * h; tl.vTip = tl.vTip + aT * h
                tl.mid = tl.mid + tl.vMid * h; tl.tip = tl.tip + tl.vTip * h
                tl.mid = base + norm(tl.mid - base) * tl.l1
                tl.tip = tl.mid + norm(tl.tip - tl.mid) * tl.l2
            }
            if (len(tl.vTip) > 280f && now - tl.lastJingle > 0.18) {
                tl.lastJingle = now; snd("tinkle"); sparkle(tl.tip + P(rnd(-4f, 4f), rnd(-4f, 4f)), small = true)
            }
        }
    }

    private fun simulateParticles(dt: Float) {
        for (q in particles) {
            q.age += dt
            when (q.kind) {
                PKind.CONFETTI -> {
                    q.v = q.v * Math.pow(0.12, dt.toDouble()).toFloat() + P(0f, -260f) * dt
                    q.v = P(q.v.x + 30 * sin(q.age * 9 + q.rot) * dt, q.v.y)
                }
                PKind.CARD -> q.v = q.v + P(0f, -380f) * dt
                PKind.INK -> q.v = q.v + P(0f, -200f) * dt
                PKind.PUFF -> q.v = q.v * Math.pow(0.05, dt.toDouble()).toFloat()
                PKind.NOTE, PKind.ZZZ -> q.v = P(q.v.x + 18 * sin(q.age * 4) * dt, q.v.y)
                PKind.SPARKLE -> {}
            }
            q.p = q.p + q.v * dt; q.rot += q.spin * dt
        }
        particles.removeAll { it.age >= it.life || it.p.y < -20 }
        if (particles.size > 160) particles.subList(0, particles.size - 160).clear()
    }

    // MARK: frame data (all transforms + alphas)
    private val frameBuf = Frame()
    /** Reused every call: valid until the next frame(). */
    internal fun frame(): Frame {
        val p = pose; val t = now.toFloat()
        val f = frameBuf; f.clear()
        for (si in 0 until 2) {
            val side = if (si == 0) -1f else 1f
            val foot = if (side < 0) p.footL else p.footR
            val col = if (side < 0) JC.amber else JC.crimson
            val hip = P(8 * side, p.hipY - 3).applying(T)
            val fw = mixp(foot, foot.applying(T), p.feetAttach)
            val footRot = atan2(T.b, T.a) * p.feetAttach; val up = P(-sin(footRot), cos(footRot))
            val (knee, end) = ik(hip, fw + up * 4f, 20f, 21f, side)
            f.legs.add(Limb(hip, knee, end, col, 7.5f, 20f, 21f))
            val sp = end - up * 4f
            f.shoes.add(TR(sp).rotated(footRot).scaledBy(-side, 1f))
        }
        val shL = P(-13.5f, 29f).applying(Tt); val shR = P(13.5f, 29f).applying(Tt)
        val (eL, hL) = ik(shL, p.handL.applying(Tt), 15.5f, 15.5f, -1f)
        val (eR, hR) = ik(shR, p.handR.applying(Tt), 15.5f, 15.5f, 1f)
        f.arms.add(Limb(shL, eL, hL, JC.crimson, 6.5f, 15.5f, 15.5f))
        f.arms.add(Limb(shR, eR, hR, JC.amber, 6.5f, 15.5f, 15.5f))
        f.sleeves.add(TR(shL)); f.sleeves.add(TR(shR)); f.hands.add(TR(hL)); f.hands.add(TR(hR))
        f.squash = Aff(1f / sqrt(max(0.4f, p.squash)), 0f, 0f, p.squash, 0f, 0f)
        val air = clamp(p.root.y / 70f); val sw = 52 * (1 - 0.45f * air) * (if (p.hipY < 25) 1.3f else 1f)
        f.shadow = Item(TR(P(p.root.x, 0f)).scaledBy(sw / 52, 1f), 0.24f * (1 - 0.6f * air))
        for (tl in tails) { val d = norm(tl.tip - tl.mid); f.tailBells.add(TR(tl.tip + d * 3f).rotated(atan2(d.y, d.x) + PIf / 2)) }
        val rot = atan2(Tt.b, Tt.a)
        val a = p.marotteAngle + rot; val dir = P(-sin(a), cos(a))
        f.marotte = Item(TR(hR - dir * (46 * p.marotteGrip)).rotated(a), p.marotte)
        val oc = hL + P(0f, 11 + 1.5f * sin(t * 2.2f))
        f.orb = Item(TR(oc), p.orb); f.swirl = Item(TR(oc).rotated(t * 2.1f), p.orb)
        f.scroll = Item(Tt.translatedBy(p.scrollPos.x, p.scrollPos.y), p.scroll)
        f.quill = Item(TR(hR).rotated(rot - 0.55f), p.quill)
        val cf = cos(p.cardFlip); f.cardFront = cf >= 0
        f.card = Item(TR(hL + P(0f, 7f)).rotated(rot + 0.2f).scaledBy(max(0.08f, abs(cf)), 1f), p.card)
        f.balls.add(Item(Tt.translatedBy(p.ball0.x, p.ball0.y), p.balls))
        f.balls.add(Item(Tt.translatedBy(p.ball1.x, p.ball1.y), p.balls))
        f.balls.add(Item(Tt.translatedBy(p.ball2.x, p.ball2.y), p.balls))
        val s = max(0.01f, p.alert * (1 + 0.12f * abs(sin(t * 5))))
        f.alert = Item(TR(P(0f, 44 + 2.5f * abs(sin(t * 5))).applying(Th)).scaledBy(s, s), if (p.alert > 0.02f) 1f else 0f)
        for (k in 0 until 3) {
            val ph = fract(t * 0.6f + k / 3f); val r = 1.5f + ph * 3
            f.dots.add(Item(TR(P(16 + k * 3 + ph * 6, 22 + ph * 22).applying(Th)).scaledBy(r / 4.5f, r / 4.5f), p.thought * sin(PIf * ph)))
        }
        val ph = fract(t * 0.7f)
        f.sweat = Item(TR(P(17f, 9 - ph * 8).applying(Th)), p.sweat * (1 - ph * 0.6f))
        val sc = P(0f, 24f).applying(Th)
        for (k in 0 until 3) {
            val an = t * 4 + k * 2 * PIf / 3
            f.stars.add(Item(TR(sc + P(cos(an) * 21, sin(an) * 5.5f)).rotated(t * 3), p.stars * (if (sin(an) < 0) 1f else 0.55f)))
        }
        return f
    }

    /** set by [particleItem]: whether a CARD particle shows its front */
    internal var particleFront = true

    internal fun particleItem(q: Particle): Item {
        particleFront = true
        val k = q.age / q.life
        return when (q.kind) {
            PKind.CONFETTI -> Item(TR(q.p).rotated(q.rot).scaledBy(cos(q.age * 8 + q.rot) * q.size, q.size), 1 - easeIn(k))
            PKind.SPARKLE -> {
                val s = q.size * (0.6f + 0.4f * sin(PIf * k)) / 6
                Item(TR(q.p).rotated(q.rot).scaledBy(s, s), sin(PIf * k))
            }
            PKind.PUFF -> { val r = q.size * (0.6f + 1.2f * easeOut(k)) / 7; Item(TR(q.p).scaledBy(r, r), 0.8f * (1 - k)) }
            PKind.INK -> Item(TR(q.p).scaledBy(q.size / 2, q.size / 2), 1 - k)
            PKind.CARD -> {
                val c = cos(q.rot)
                particleFront = c >= 0
                Item(TR(q.p).rotated(q.rot * 0.3f).scaledBy(max(0.08f, abs(c)), 1f), 1 - easeIn(k))
            }
            PKind.ZZZ, PKind.NOTE -> {
                val sz = q.size * (if (q.kind == PKind.ZZZ) 0.7f + 0.6f * k else 1f) / 12
                Item(TR(q.p).rotated(q.rot).scaledBy(sz, sz), sin(PIf * k))
            }
        }
    }

    /**
     * Draws the jester into [canvas] (a view of [width] x [height] px). Flips to y-up once; ground sits [groundPad] px above the bottom.
     * Needs android.graphics (not used by the JVM unit tests).
     */
    fun draw(canvas: Canvas, width: Float, height: Float) {
        val pr = painter ?: JesterPainter().also { painter = it }
        pr.useSprites = useSprites
        canvas.save()
        canvas.translate(width / 2, height - groundPad)
        canvas.scale(zoom, -zoom)
        pr.draw(this, canvas)
        canvas.restore()
    }
}

// MARK: renderer (android.graphics; only touched from JesterRig.draw)
internal class JesterPainter {
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textSize = 12f; isFakeBoldText = true }
    private val textS = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; textSize = 12f; isFakeBoldText = true; strokeWidth = 0.7f; color = 0xFF000000.toInt(); strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val mtx = Matrix()
    private val mv = FloatArray(9)
    private var c: Canvas = Canvas()
    @JvmField var useSprites = true
    private var ga = 1f   // global alpha (CGContext.setAlpha)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowShader: RadialGradient? = null
    private var glowAccent = 0
    private val torsoShader by lazy {
        LinearGradient(-20f, 0f, 20f, 0f,
            intArrayOf(rgb(255, 255, 255, 0.16f), rgb(0, 0, 0, 0f), rgb(40, 0, 10, 0.22f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    private val orbShader by lazy {
        RadialGradient(-2.5f, 3f, 11.4f,
            intArrayOf(rgb(255, 210, 220, 0.95f), rgb(232, 130, 140, 0.9f), rgb(150, 30, 60, 0.85f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballCols = intArrayOf(JC.crimson, JC.amber, JC.red)
    private val capXs = floatArrayOf(-11f, 0f, 11f)
    private val torsoBody = Path().apply {
        moveTo(-16f, -3f)
        var x = -16f
        while (x < 16f) { lineTo(x + 4, -10f); lineTo(x + 8, -3f); x += 8f }
        quadTo(22f, 13f, 13f, 32f)
        quadTo(0f, 37f, -13f, 32f)
        quadTo(-22f, 13f, -16f, -3f)
        close()
    }
    private val torsoDiamonds = Path()
    private val capPath = Path().apply { moveTo(-18f, 7f); cubicTo(-19f, 25f, 19f, 25f, 18f, 7f); close() }
    private val tl = FloatArray(30); private val tr = FloatArray(30)

    private fun col(p: Paint, color: Int) {
        p.color = color
        p.alpha = (((color ushr 24) and 255) * ga + 0.5f).toInt()
    }
    private fun concat(a: Aff) {
        mv[0] = a.a; mv[1] = a.c; mv[2] = a.tx; mv[3] = a.b; mv[4] = a.d; mv[5] = a.ty; mv[6] = 0f; mv[7] = 0f; mv[8] = 1f
        mtx.setValues(mv); c.concat(mtx)
    }
    private inline fun at(it: Item, f: () -> Unit) {
        if (it.alpha <= 0.02f) return
        c.save(); concat(it.xf); ga = if (it.alpha < 1f) it.alpha else 1f
        f()
        ga = 1f; c.restore()
    }
    private inline fun atXf(xf: Aff, f: () -> Unit) { c.save(); concat(xf); f(); c.restore() }

    // CG-style helpers: rects are (x, y, w, h)
    private fun fillOval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        col(fillP, color); rect.set(x, y, x + w, y + h); c.drawOval(rect, fillP)
    }
    private fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        col(fillP, color); c.drawRect(x, y, x + w, y + h, fillP)
    }
    private fun fillPath(color: Int) { col(fillP, color); c.drawPath(path, fillP) }
    private fun strokePath(color: Int, w: Float) { col(strokeP, color); strokeP.strokeWidth = w; c.drawPath(path, strokeP) }
    private fun line(ax: Float, ay: Float, bx: Float, by: Float, color: Int, w: Float) {
        path.reset(); path.moveTo(ax, ay); path.lineTo(bx, by); strokePath(color, w)
    }
    private fun outlined(fill: Int, line: Float = 2.1f) { fillPath(fill); strokePath(JC.ink, line) }
    private fun ovalPath(x: Float, y: Float, w: Float, h: Float) { path.reset(); rect.set(x, y, x + w, y + h); path.addOval(rect, Path.Direction.CW) }
    private fun outlinedOval(x: Float, y: Float, w: Float, h: Float, fill: Int, line: Float) { ovalPath(x, y, w, h); outlined(fill, line) }
    private fun rrectPath(x: Float, y: Float, w: Float, h: Float, r: Float) {
        path.reset(); rect.set(x, y, x + w, y + h); path.addRoundRect(rect, r, r, Path.Direction.CW)
    }
    private fun outlinedRRect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, line: Float) { rrectPath(x, y, w, h, r); outlined(fill, line) }
    private fun Path.move(p: Pt) = moveTo(p.x, p.y)
    private fun Path.line(p: Pt) = lineTo(p.x, p.y)

    private fun diamond(cx: Float, cy: Float, rx: Float, ry: Float) {
        path.moveTo(cx, cy + ry); path.lineTo(cx + rx, cy); path.lineTo(cx, cy - ry); path.lineTo(cx - rx, cy); path.close()
    }

    fun draw(rig: JesterRig, canvas: Canvas) {
        if (useSprites) drawSprites(rig, canvas) else drawVector(rig, canvas)
    }

    private fun drawVector(rig: JesterRig, canvas: Canvas) {
        c = canvas; ga = 1f
        val f = rig.frame()
        val pose = rig.pose
        // glow (the Mac app draws this as a GPU gradient layer)
        if (rig.glowAlpha > 0.01f) {
            if (glowShader == null || glowAccent != rig.accent) {
                glowAccent = rig.accent
                val a0 = (rig.accent and 0x00FFFFFF) or (0x52 shl 24)   // 0.32
                glowShader = RadialGradient(0f, 0f, 95f, a0, rig.accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            glowPaint.shader = glowShader; glowPaint.alpha = (rig.glowAlpha * 255f + 0.5f).toInt()
            canvas.save(); canvas.translate(rig.glowCenter.x, rig.glowCenter.y)
            canvas.drawCircle(0f, 0f, 95f, glowPaint); canvas.restore()
        }
        at(f.shadow) { shadowLocal() }
        c.save(); concat(f.squash)
        for (i in 0 until rig.tails.size) {
            val t = rig.tails[i]
            if (!t.ready) continue
            tailPath(t); outlined(t.color, 1.9f)
            atXf(f.tailBells[i]) { bell(0f, 0f, 4.2f, 0f) }
        }
        for (i in 0 until f.legs.size) { limb(f.legs[i]); atXf(f.shoes[i]) { shoeLocal() } }
        atXf(rig.Tt) { torsoLocal() }
        for (l in f.arms) limb(l)
        for (i in 0 until f.sleeves.size) atXf(f.sleeves[i]) { sleeveLocal(f.arms[i].color) }
        atXf(rig.Tt) { ruffLocal() }
        atXf(rig.Th) { headBaseLocal(); featuresLocal(rig); capLocal() }
        at(f.marotte) { marotteLocal() }
        at(f.orb) { orbLocal() }
        at(f.swirl) { swirlLocal() }
        at(f.scroll) { scrollLocal(pose.writeProgress) }
        at(f.quill) { quillLocal() }
        at(f.card) { cardLocal(f.cardFront) }
        for (i in 0 until f.balls.size) at(f.balls[i]) { ballLocal(ballCols[i]) }
        for (h in f.hands) atXf(h) { handLocal() }
        at(f.alert) { alertLocal() }
        for (d in f.dots) at(d) { dotLocal() }
        at(f.sweat) { sweatLocal() }
        for (s in f.stars) at(s) { star(0f, 0f, 4.2f, 0f, JC.gold, 5) }
        c.restore()
        for (qi in 0 until rig.particles.size) { val q = rig.particles[qi]; val it = rig.particleItem(q); val front = rig.particleFront; at(it) { particleLocal(q.kind, q.color, front) } }
    }


    // MARK: bitmap sprites (cutout renderer, mirrors JesterLayers): every rigid part is rasterised once at zoom * 1.5,
    // then each frame is only canvas.drawBitmap(bmp, matrix, paint) with the frame() transforms.
    private class Sprite(val bmp: Bitmap, val minX: Float, val maxY: Float, val inv: Float)

    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val slots = arrayOfNulls<Sprite>(24)
    private val keyed = LongSparseArray<Sprite>()
    private var bakeScale = 0f
    private var bakeBmp: Bitmap? = null; private var bakeSaved: Canvas = Canvas()
    private var bakeMinX = 0f; private var bakeMaxY = 0f; private var bakeInv = 1f
    private val featLast = IntArray(9) { Int.MIN_VALUE }
    private var scrollLast = -1
    private var glowBaked = 0

    private fun ensureScale(zoom: Float) {
        val s = zoom * 1.5f
        if (s == bakeScale) return
        bakeScale = s
        java.util.Arrays.fill(slots, null); keyed.clear()
        java.util.Arrays.fill(featLast, Int.MIN_VALUE); scrollLast = -1
    }

    private fun begin(x0: Float, y0: Float, x1: Float, y1: Float, k: Float) {
        val s = bakeScale * k
        val w = max(1, ceil((x1 - x0) * s).toInt()); val h = max(1, ceil((y1 - y0) * s).toInt())
        bakeCount++
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bmp); bc.translate(-x0 * s, y1 * s); bc.scale(s, -s)
        bakeBmp = bmp; bakeSaved = c; c = bc; ga = 1f
        bakeMinX = x0; bakeMaxY = y1; bakeInv = 1f / s
    }
    private fun end(): Sprite { c = bakeSaved; ga = 1f; return Sprite(bakeBmp!!, bakeMinX, bakeMaxY, bakeInv) }

    private inline fun slot(i: Int, x0: Float, y0: Float, x1: Float, y1: Float, k: Float = 1f, draw: () -> Unit): Sprite {
        slots[i]?.let { return it }
        begin(x0, y0, x1, y1, k); draw()
        return end().also { slots[i] = it }
    }
    private inline fun keyedSprite(key: Long, x0: Float, y0: Float, x1: Float, y1: Float, k: Float = 1f, draw: () -> Unit): Sprite {
        keyed.get(key)?.let { return it }
        begin(x0, y0, x1, y1, k); draw()
        return end().also { keyed.put(key, it) }
    }

    /** matrix rows [m0 m1 m2; m3 m4 m5] in sprite-local (y-up) space */
    private fun put(sp: Sprite, m0: Float, m1: Float, m2: Float, m3: Float, m4: Float, m5: Float, alpha: Float) {
        mv[0] = m0; mv[1] = m1; mv[2] = m2; mv[3] = m3; mv[4] = m4; mv[5] = m5; mv[6] = 0f; mv[7] = 0f; mv[8] = 1f
        mtx.setValues(mv); mtx.preTranslate(sp.minX, sp.maxY); mtx.preScale(sp.inv, -sp.inv)
        spritePaint.alpha = (alpha * 255f + 0.5f).toInt()
        c.drawBitmap(sp.bmp, mtx, spritePaint)
    }
    private fun put(sp: Sprite, xf: Aff, alpha: Float = 1f) = put(sp, xf.a, xf.c, xf.tx, xf.b, xf.d, xf.ty, alpha)
    private fun put(sp: Sprite, it: Item) { if (it.alpha > 0.02f) put(sp, it.xf, min(1f, it.alpha)) }

    private fun bone(a: Pt, b: Pt, sp: Sprite) {
        val dx = b.x - a.x; val dy = b.y - a.y; val l = max(1e-6f, hypot(dx, dy)); val cs = dx / l; val sn = dy / l
        put(sp, cs, -sn, a.x, sn, cs, a.y, 1f)
    }
    private fun limbSprite(l: Limb, len: Float, ink: Boolean): Sprite {
        val w = if (ink) l.w + 4.2f else l.w
        val key = (1L shl 48) or ((if (ink) 1L else 2L) shl 52) or (l.color.toLong() and 0xFFFFFFFFL) or
            ((l.w * 10f).toInt().toLong() shl 32) or ((len * 10f).toInt().toLong() shl 40)
        val h = w / 2 + 1.5f
        return keyedSprite(key, -h, -h, len + h, h) {
            if (ink) line(0f, 0f, len, 0f, JC.ink, w)
            else { line(0f, 0f, len, 0f, l.color, l.w); line(0f, 0f, len, 0f, rgb(255, 255, 255, 0.18f), l.w * 0.3f) }
        }
    }
    /** ink pass for both bones, then colour pass for both, so the joint shows no seam */
    private fun drawLimb(l: Limb) {
        bone(l.a, l.j, limbSprite(l, l.l1, true)); bone(l.j, l.b, limbSprite(l, l.l2, true))
        bone(l.a, l.j, limbSprite(l, l.l1, false)); bone(l.j, l.b, limbSprite(l, l.l2, false))
    }

    private fun particleSprite(kind: PKind, color: Int, front: Boolean): Sprite {
        if (kind == PKind.CARD) return cardSprite(front)
        val key = (3L shl 48) or (kind.ordinal.toLong() shl 40) or (color.toLong() and 0xFFFFFFFFL)
        return when (kind) {
            PKind.CONFETTI -> keyedSprite(key, -1f, -1f, 1f, 1f, 8f) { particleLocal(kind, color, true) }
            PKind.SPARKLE -> keyedSprite(key, -8f, -8f, 8f, 8f, 1.5f) { particleLocal(kind, color, true) }
            PKind.PUFF -> keyedSprite(key, -9f, -9f, 9f, 9f, 2f) { particleLocal(kind, color, true) }
            PKind.INK -> keyedSprite(key, -4f, -4f, 4f, 4f, 3f) { particleLocal(kind, color, true) }
            else -> keyedSprite(key, -5f, -7f, 18f, 18f, 1.5f) { particleLocal(kind, color, true) }
        }
    }
    private fun cardSprite(front: Boolean) =
        slot(if (front) 14 else 15, -8f, -10f, 8f, 10f, 1.5f) { cardLocal(front) }

    private fun drawSprites(rig: JesterRig, canvas: Canvas) {
        ensureScale(rig.zoom)
        c = canvas; ga = 1f
        val f = rig.frame()
        val pose = rig.pose
        if (rig.glowAlpha > 0.01f) {
            if (slots[0] == null || glowBaked != rig.accent) {
                glowBaked = rig.accent
                val a0 = (rig.accent and 0x00FFFFFF) or (0x52 shl 24)
                glowShader = RadialGradient(0f, 0f, 95f, a0, rig.accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
                slots[0] = null
                slot(0, -96f, -96f, 96f, 96f, 0.4f) {
                    glowPaint.shader = glowShader; glowPaint.alpha = 255; c.drawCircle(0f, 0f, 95f, glowPaint)
                }
            }
            put(slots[0]!!, 1f, 0f, rig.glowCenter.x, 0f, 1f, rig.glowCenter.y, rig.glowAlpha)
        }
        put(slot(1, -28f, -5f, 28f, 5f) { shadowLocal() }, f.shadow)
        c.save(); concat(f.squash)
        val bellSp = slot(2, -8f, -8f, 8f, 8f, 1.5f) { bell(0f, 0f, 4.2f, 0f) }
        for (i in 0 until rig.tails.size) {
            val t = rig.tails[i]
            if (!t.ready) continue
            tailPath(t); outlined(t.color, 1.9f)
            put(bellSp, f.tailBells[i])
        }
        val shoeSp = slot(3, -22f, -3f, 9f, 13f, 1.5f) { shoeLocal() }
        for (i in 0 until f.legs.size) { drawLimb(f.legs[i]); put(shoeSp, f.shoes[i]) }
        put(slot(4, -27f, -17f, 27f, 40f) { torsoLocal() }, rig.Tt)
        for (l in f.arms) drawLimb(l)
        for (i in 0 until f.sleeves.size) {
            val cl = f.arms[i].color
            put(slot(if (cl == JC.crimson) 5 else 6, -8f, -8f, 8f, 8f, 1.5f) { sleeveLocal(cl) }, f.sleeves[i])
        }
        put(slot(7, -22f, 25f, 22f, 44f) { ruffLocal() }, rig.Tt)
        put(slot(8, -20f, -19f, 20f, 19f) { headBaseLocal() }, rig.Th)
        if (rig.featuresChanged(featLast)) slots[9] = null
        put(slot(9, -18f, -20f, 18f, 20f) { featuresLocal(rig) }, rig.Th)
        put(slot(10, -21f, -1f, 21f, 29f) { capLocal() }, rig.Th)
        if (f.marotte.alpha > 0.02f) put(slot(11, -16f, -7f, 16f, 68f) { marotteLocal() }, f.marotte)
        if (f.orb.alpha > 0.02f) put(slot(12, -11f, -11f, 11f, 11f, 1.5f) { orbLocal() }, f.orb)
        if (f.swirl.alpha > 0.02f) put(slot(13, -10f, -10f, 10f, 10f, 1.5f) { swirlLocal() }, f.swirl)
        if (f.scroll.alpha > 0.02f) {
            val wp = Math.round(pose.writeProgress * 20f)
            if (wp != scrollLast) { scrollLast = wp; slots[16] = null }
            put(slot(16, -19f, -15f, 19f, 15f) { scrollLocal(wp / 20f) }, f.scroll)
        }
        if (f.quill.alpha > 0.02f) put(slot(17, -10f, -10f, 10f, 27f, 1.5f) { quillLocal() }, f.quill)
        if (f.card.alpha > 0.02f) put(cardSprite(f.cardFront), f.card)
        for (i in 0 until f.balls.size) {
            val ci = ballCols[i]
            if (f.balls[i].alpha > 0.02f) put(slot(18 + i, -7f, -7f, 7f, 7f, 1.5f) { ballLocal(ci) }, f.balls[i])
        }
        for (h in f.hands) put(slot(21, -7f, -7f, 7f, 7f, 1.5f) { handLocal() }, h)
        if (f.alert.alpha > 0.02f) put(slot(22, -6f, -9f, 6f, 15f, 1.5f) { alertLocal() }, f.alert)
        for (d in f.dots) if (d.alpha > 0.02f) put(slot(23, -6.5f, -6.5f, 6.5f, 6.5f, 2f) { dotLocal() }, d)
        if (f.sweat.alpha > 0.02f) put(keyedSprite(5L shl 48, -7f, -5f, 7f, 7f, 1.5f) { sweatLocal() }, f.sweat)
        for (s in f.stars) if (s.alpha > 0.02f) put(keyedSprite(6L shl 48, -7f, -7f, 7f, 7f, 1.5f) { star(0f, 0f, 4.2f, 0f, JC.gold, 5) }, s)
        c.restore()
        for (qi in 0 until rig.particles.size) {
            val q = rig.particles[qi]; val it = rig.particleItem(q)
            if (it.alpha > 0.02f) put(particleSprite(q.kind, q.color, rig.particleFront), it)
        }
    }

    private fun particleLocal(kind: PKind, color: Int, front: Boolean) {
        when (kind) {
            PKind.CONFETTI -> fillRect(-0.5f, -0.3f, 1f, 0.6f, color)
            PKind.SPARKLE -> {
                path.reset()
                for (i in 0 until 8) {
                    val a = i * PIf / 4; val rr = if (i % 2 == 0) 6f else 1.7f
                    if (i == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr)
                }
                path.close(); fillPath(color)
            }
            PKind.PUFF -> fillOval(-7f, -7f, 14f, 14f, color)
            PKind.INK -> fillOval(-2f, -2f, 4f, 4f, color)
            PKind.CARD -> cardLocal(front)
            PKind.ZZZ, PKind.NOTE -> {
                val s = if (kind == PKind.ZZZ) "z" else "♪"
                c.save(); c.scale(1f, -1f)   // glyphs drawn un-flipped
                col(textP, color); col(textS, 0xFF000000.toInt())
                c.drawText(s, 0f, 0f, textP); c.drawText(s, 0f, 0f, textS)
                c.restore()
            }
        }
    }

    // MARK: local drawing primitives
    private fun limb(l: Limb) {
        path.reset(); path.move(l.a); path.line(l.j); path.line(l.b)
        strokePath(JC.ink, l.w + 4.2f); strokePath(l.color, l.w); strokePath(rgb(255, 255, 255, 0.18f), l.w * 0.3f)
    }
    private fun shadowLocal() { fillOval(-26f, -3.5f, 52f, 7f, rgb(0, 0, 0)) }
    private fun sleeveLocal(color: Int) { outlinedOval(-6f, -5.5f, 12f, 11f, color, 1.8f) }
    private fun handLocal() { outlinedOval(-4.8f, -4.8f, 9.6f, 9.6f, JC.cream, 1.8f) }
    /** left shoe, sole at the origin, curled toe pointing to -x (the right shoe is mirrored by its transform) */
    private fun shoeLocal() {
        path.reset()
        path.moveTo(6f, 0f); path.lineTo(-8f, 0f)
        path.quadTo(-15f, 0f, -16f, 8f)
        path.quadTo(-13f, 4f, -10f, 5.5f)
        path.quadTo(-6f, 7.5f, -3f, 7f)
        path.lineTo(5f, 7f); path.quadTo(8f, 3.5f, 6f, 0f); path.close()
        outlined(JC.crimsonDark, 1.8f)
        bell(-16.5f, 9f, 2.4f, 0f)
    }
    private fun bell(cx: Float, cy: Float, r: Float, rot: Float) {
        outlinedOval(cx - r, cy - r, 2 * r, 2 * r, JC.gold, max(1f, r * 0.35f))
        fillOval(cx - r * 0.55f, cy + r * 0.1f, r * 0.6f, r * 0.5f, rgb(255, 245, 210, 0.9f))
        line(cx - r * 0.5f * cos(rot), cy - r * 0.5f * sin(rot) - r * 0.25f,
            cx + r * 0.5f * cos(rot), cy + r * 0.5f * sin(rot) - r * 0.25f, JC.goldDark, max(0.7f, r * 0.22f))
    }
    private fun torsoLocal() {
        val body = torsoBody
        col(fillP, JC.crimson); c.drawPath(body, fillP)
        c.save(); c.clipPath(body)
        if (torsoDiamonds.isEmpty) {
            val keep = Path(path); path.reset()
            for (i in 0 until 5) for (j in 0 until 7) {
                diamond(-30f + 10 * j + (if (i % 2 == 0) 0f else 5f), -10f + 11 * i, 5f, 5.5f)
            }
            torsoDiamonds.set(path); path.set(keep)
        }
        col(fillP, JC.amber); c.drawPath(torsoDiamonds, fillP)
        gradPaint.shader = torsoShader; gradPaint.alpha = (255 * ga + 0.5f).toInt()
        c.drawRect(-20f, -20f, 20f, 50f, gradPaint)
        fillRect(-24f, 4.5f, 48f, 4.5f, JC.goldDark)
        fillRect(-24f, 6f, 48f, 2f, JC.gold)
        c.restore()
        col(strokeP, JC.ink); strokeP.strokeWidth = 2.2f; c.drawPath(body, strokeP)
        outlinedRRect(-3.5f, 3.5f, 7f, 6.5f, 1.5f, JC.gold, 1.4f)
        var bx = -12f
        while (bx <= 12.001f) { bell(bx, -11.5f, 2.3f, 0f); bx += 8f }
    }
    private fun ruffLocal() {
        for (row in 0 until 2) for (i in 0 until 7) {
            val u = i / 6f; val x = -15 + 30 * u
            val y = 34.5f - 2.2f * sin(PIf * u) + (if (row == 0) 2.2f else 0f)
            val r = if (row == 0) 4.6f else 5f
            outlinedOval(x - r, y - r, 2 * r, 2 * r, if (row == 0) JC.blush else JC.cream, 1.5f)
        }
    }
    private fun tailPath(t: Tail) {
        val cx = t.mid.x * 2 - (t.base.x + t.tip.x) * 0.5f; val cy = t.mid.y * 2 - (t.base.y + t.tip.y) * 0.5f
        val cc = Pt(cx, cy)
        val n = 14
        for (k in 0..n) {
            val tt = k / n.toFloat()
            val pt = bez(t.base, cc, t.tip, tt)
            val tan = norm(bez(t.base, cc, t.tip, min(1f, tt + 0.02f)) - bez(t.base, cc, t.tip, max(0f, tt - 0.02f)))
            val nx = -tan.y; val ny = tan.x; val w = mixf(5f, 1.4f, tt)
            tl[k * 2] = pt.x + nx * w; tl[k * 2 + 1] = pt.y + ny * w
            tr[k * 2] = pt.x - nx * w; tr[k * 2 + 1] = pt.y - ny * w
        }
        path.reset(); path.moveTo(tl[0], tl[1])
        for (k in 1..n) path.lineTo(tl[k * 2], tl[k * 2 + 1])
        for (k in n downTo 0) path.lineTo(tr[k * 2], tr[k * 2 + 1])
        path.close()
    }
    /** face, cheeks and nose (head space, origin = face centre) */
    private fun headBaseLocal() {
        ovalPath(-17f, -16f, 34f, 32f); outlined(JC.skin, 2.2f)
        c.save(); c.clipPath(path)
        fillOval(-20f, -24f, 40f, 18f, rgb(225, 150, 140, 0.25f))
        c.restore()
        fillOval(-14f, -7.5f, 7.5f, 5.5f, rgb(232, 130, 140, 0.55f)); fillOval(6.5f, -7.5f, 7.5f, 5.5f, rgb(232, 130, 140, 0.55f))
        outlinedOval(-2.6f, -4.6f, 5.2f, 5.2f, JC.red, 1.2f)
        fillOval(-1.4f, -2.4f, 1.6f, 1.4f, rgb(255, 255, 255, 0.7f))
    }
    /** eyes, brows, mouth, tongue: the only part of the head that changes every frame */
    private fun featuresLocal(rig: JesterRig) {
        val p = rig.pose
        for (side in floatArrayOf(-1f, 1f)) {
            val cx = 6.2f * side; val cy = 1.5f
            if (p.dizzy > 0.5f) {
                val spin = rig.now.toFloat() * 7 * side
                path.reset()
                for (k in 0..24) {
                    val a = k * 0.55f + spin; val r = k * 0.17f
                    if (k == 0) path.moveTo(cx + cos(a) * r, cy + sin(a) * r) else path.lineTo(cx + cos(a) * r, cy + sin(a) * r)
                }
                strokePath(JC.ink, 1.3f)
            } else if (p.happy > 0.5f) {
                path.reset(); path.moveTo(cx - 3.4f, cy - 1f); path.quadTo(cx, cy + 4.5f, cx + 3.4f, cy - 1f); strokePath(JC.ink, 1.9f)
            } else if (p.eyeOpen < 0.15f) {
                path.reset(); path.moveTo(cx - 3.6f, cy + 0.5f); path.quadTo(cx, cy - 2.8f, cx + 3.6f, cy + 0.5f); strokePath(JC.ink, 1.7f)
            } else {
                val h = 5 * p.eyeOpen
                ovalPath(cx - 4.1f, cy - h, 8.2f, 2 * h); outlined(JC.white, 1.4f)
                c.save(); c.clipPath(path)
                val px = cx + p.look.x * 1.7f; val py = cy + p.look.y * 2 * min(1f, p.eyeOpen)
                fillOval(px - 2.6f, py - 2.6f, 5.2f, 5.2f, JC.ink)
                fillOval(px + 0.2f, py + 0.5f, 1.8f, 1.8f, JC.white)
                c.restore()
            }
            val b = p.brow
            val iy = cy + 7 + 1.6f * b + (if (b < 0) -b * 2.2f else 0f)
            val oy = cy + 7 + 1.6f * b + (if (b < 0) b * 0.8f else 0f)
            line(cx - 2 * side, iy, cx + 4 * side, oy, JC.ink, 1.6f)
        }
        val mx = 0f; val my = -8.5f; val s = p.smile; val o = clamp(p.mouthOpen)
        if (p.dizzy > 0.5f) {
            path.reset(); path.moveTo(mx - 5f, my)
            for (k in 1..8) path.lineTo(mx - 5 + k * 1.25f, my + (if (k % 2 == 0) 0f else 1.3f))
            strokePath(JC.ink, 1.4f)
        } else if (o < 0.12f) {
            path.reset(); path.moveTo(mx - 5f, my + 0.8f * s); path.quadTo(mx, my - 4.5f * s, mx + 5f, my + 0.8f * s); strokePath(JC.ink, 1.7f)
        } else {
            val wdt = 5 + 1.2f * o
            path.reset(); path.moveTo(mx - wdt, my + 1.2f * s)
            path.quadTo(mx, my - 1.5f * s, mx + wdt, my + 1.2f * s)
            path.quadTo(mx, my - 2 * s - 11 * o, mx - wdt, my + 1.2f * s)
            path.close()
            fillPath(JC.mouth)
            c.save(); c.clipPath(path)
            fillOval(mx - 3.5f, my - 2 * s - 9 * o, 7f, 5f, JC.rose)
            c.restore()
            strokePath(JC.ink, 1.5f)
        }
        if (p.tongue > 0.5f && o < 0.3f) outlinedOval(mx + 2, my - 3.2f, 3.6f, 3.6f, JC.rose, 1.1f)
    }
    private fun capLocal() {
        val cap = capPath
        c.save(); c.clipPath(cap)
        fillRect(-20f, 0f, 20f, 30f, JC.crimson)
        fillRect(0f, 0f, 20f, 30f, JC.amber)
        c.restore()
        col(strokeP, JC.ink); strokeP.strokeWidth = 2.1f; c.drawPath(cap, strokeP)
        path.reset()
        path.moveTo(-19f, 5f); path.quadTo(0f, 2f, 19f, 5f)
        path.lineTo(18.5f, 10.5f); path.quadTo(0f, 8f, -18.5f, 10.5f); path.close()
        outlined(JC.crimsonDark, 1.8f)
        for (bi in 0 until 3) {
            val bx = capXs[bi]
            path.reset(); diamond(bx, 7 - (if (bx == 0f) 1.2f else 0.5f), 2f, 2.4f); fillPath(JC.gold)
        }
    }
    /** scepter with a mini jester head; origin = bottom of the stick, pointing up */
    private fun marotteLocal() {
        val L = 46f
        line(0f, 0f, 0f, L, JC.ink, 5.6f)
        line(0f, 0f, 0f, L, JC.wood, 2.8f)
        bell(0f, 0f, 2.4f, 0f)
        val hx = 0f; val hy = L + 5
        for (s in floatArrayOf(-1f, 1f)) {
            val tx = hx + -sin(s * 0.9f) * 11; val ty = hy + cos(s * 0.9f) * 11
            path.reset(); path.moveTo(hx - 3 * s, hy + 2); path.quadTo(hx - 6 * s, hy + 8, tx, ty); path.lineTo(hx + 2 * s, hy + 3); path.close()
            outlined(if (s < 0) JC.crimson else JC.amber, 1.3f)
            bell(tx, ty, 1.9f, 0f)
        }
        outlinedOval(hx - 5, hy - 5, 10f, 10f, JC.skin, 1.4f)
        fillOval(hx - 2.6f, hy, 1.4f, 1.6f, JC.ink); fillOval(hx + 1.2f, hy, 1.4f, 1.6f, JC.ink)
        fillOval(hx - 1, hy - 2, 2f, 2f, JC.red)
    }
    private fun orbLocal() {
        ovalPath(-8.5f, -8.5f, 17f, 17f)
        c.save(); c.clipPath(path)
        gradPaint.shader = orbShader; gradPaint.alpha = (255 * ga + 0.5f).toInt()
        c.drawRect(-9f, -9f, 9f, 9f, gradPaint)
        c.restore()
        strokePath(JC.ink, 1.6f)
        fillOval(-5f, 2.5f, 3.5f, 2.5f, rgb(255, 255, 255, 0.85f))
    }
    private fun swirlLocal() {
        for (k in 0 until 2) {
            val a0 = k * 2.6f; val r = 4 + k * 2.2f
            rect.set(-r, -r, r, r)
            path.reset(); path.addArc(rect, Math.toDegrees(a0.toDouble()).toFloat(), Math.toDegrees(2.2).toFloat())
            strokePath(rgb(255, 240, 200, 0.7f), 1.3f)
        }
    }
    private fun scrollLocal(progress: Float) {
        val rx = -15f; val ry = -11f; val rw = 30f; val rh = 22f
        path.reset(); path.addRect(rx, ry, rx + rw, ry + rh, Path.Direction.CW); outlined(JC.cream, 1.6f)
        val maxY = ry + rh
        for (k in 0 until 4) {
            val prog = clamp(progress - k)
            if (prog <= 0f) continue
            val y = maxY - 5 - k * 4.2f; val x0 = rx + 3; val x1 = x0 + 24 * prog
            path.reset(); path.moveTo(x0, y)
            var x = x0; var up = true
            while (x < x1) { x = min(x1, x + 1.6f); path.lineTo(x, y + (if (up) 0.7f else -0.5f)); up = !up }
            strokePath(rgb(60, 30, 40, 0.8f), 0.9f)
        }
        for (yy in floatArrayOf(maxY, ry)) outlinedRRect(rx - 2.5f, yy - 2.3f, rw + 5, 4.6f, 2.3f, JC.amberDark, 1.4f)
    }
    private fun quillLocal() {
        path.reset()
        path.moveTo(0f, -4f); path.quadTo(-9f, 10f, 1f, 24f); path.quadTo(8f, 12f, 0f, -4f); path.close()
        outlined(JC.white, 1.3f)
        line(0f, -6f, 0.8f, 22f, JC.rose, 1f)
        line(0f, -4f, 0f, -8f, JC.ink, 1.6f)
    }
    private fun cardLocal(front: Boolean) {
        outlinedRRect(-5f, -7f, 10f, 14f, 1.6f, if (front) JC.white else JC.crimson, 1.2f)
        if (front) { path.reset(); diamond(0f, 0f, 2.6f, 3.5f); fillPath(JC.crimson) }
    }
    private fun ballLocal(color: Int) {
        outlinedOval(-4.6f, -4.6f, 9.2f, 9.2f, color, 1.6f)
        fillOval(-2.6f, 0.6f, 2.6f, 2f, rgb(255, 255, 255, 0.6f))
    }
    private fun alertLocal() {
        outlinedRRect(-2.6f, 0f, 5.2f, 13f, 2.6f, JC.red, 1.6f)
        outlinedOval(-2.8f, -7f, 5.6f, 5.6f, JC.red, 1.6f)
    }
    private fun dotLocal() { outlinedOval(-4.5f, -4.5f, 9f, 9f, JC.white, 1.1f) }
    private fun sweatLocal() {
        path.reset(); path.moveTo(0f, 5f); path.quadTo(4.5f, -2f, 0f, -2.5f); path.quadTo(-4.5f, -2f, 0f, 5f)
        outlined(JC.sweat, 1.2f)
    }
    private fun star(cx: Float, cy: Float, r: Float, rot: Float, fill: Int, points: Int) {
        path.reset()
        for (k in 0 until points * 2) {
            val a = rot + k * PIf / points + PIf / 2; val rr = if (k % 2 == 0) r else r * 0.45f
            if (k == 0) path.moveTo(cx + cos(a) * rr, cy + sin(a) * rr) else path.lineTo(cx + cos(a) * rr, cy + sin(a) * rr)
        }
        path.close()
        outlined(fill, 1.1f)
    }
}

/** debug: sprite bitmaps baked since the last timing log (each bake = a GPU texture upload) */
@JvmField var bakeCount = 0
