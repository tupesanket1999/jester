package io.github.tupesanket1999.jester.overlay

import io.github.tupesanket1999.jester.bot.BotPack
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the bot pack with nearest-neighbour scaling (integer pixel size, no filtering) and the Mac's gentle bob.
 * "remind"/"waiting" bob 2.2x higher and faster, which is the "needs attention" bounce.
 */
class RobotView(ctx: Context, pack: BotPack) : View(ctx) {
    var pack: BotPack = pack
        set(v) { field = v; frames.clear(); requestLayout(); invalidate() }
    var state = "idle"
        set(v) { if (field != v) { field = v; frameIdx = 0; invalidate() } }
    var running = true
        set(v) { field = v; if (v) postInvalidateOnAnimation() }

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val frames = HashMap<String, Bitmap>()
    private val dst = Rect()
    private val t0 = SystemClock.uptimeMillis()
    private var frameIdx = 0
    private var lastFrameAt = 0L

    /** Screen pixels per bot pixel: the pack's scale in dp, rounded to a whole number so pixels stay crisp. */
    val px: Int get() = maxOf(1, (pack.scale * resources.displayMetrics.density * 0.75f).roundToInt())
    private val bobRoom: Int get() = (pack.bob * 2.2f * px).roundToInt() + px

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(pack.width * px, pack.height * px + bobRoom)
    }

    private fun bitmap(state: String, i: Int): Bitmap = frames.getOrPut("$state#$i") {
        val pixels = pack.render(state, i)
        Bitmap.createBitmap(pixels, pack.width, pack.height, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val n = pack.frames(state).size.coerceAtLeast(1)
        if (now - lastFrameAt >= 1000.0 / pack.fpsFor(state)) { frameIdx = (frameIdx + 1) % n; lastFrameAt = now }
        val alert = state == "remind" || state == "waiting"
        val amp = when { state == "sleep" -> pack.bob * 0.5f; alert -> pack.bob * 2.2f; else -> pack.bob }
        val speed = if (alert) 4.0 else 1.8
        val t = (now - t0) / 1000.0
        // alert: a hop (|sin|) instead of a float, so it reads as bouncing
        val wave = if (alert) -abs(sin(t * speed)).toFloat() else sin(t * speed).toFloat()
        val y = (bobRoom / 2f + wave * amp * px / 2f).roundToInt().coerceIn(0, bobRoom)
        dst.set(0, y, pack.width * px, y + pack.height * px)
        canvas.drawBitmap(bitmap(state, frameIdx), null, dst, paint)
        // ~20 fps like the Mac mascot; stops when hidden or the screen is off
        if (running && isShown) postInvalidateDelayed(50)
    }
}
