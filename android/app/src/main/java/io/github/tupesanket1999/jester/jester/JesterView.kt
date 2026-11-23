package io.github.tupesanket1999.jester.jester

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Hosts a [JesterRig]: steps it with real time every frame, draws it, and paces invalidation
 * (maxFps when something fast is on screen, 60 when calm, ~20 asleep). Stops when hidden or detached.
 */
class JesterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val rig = JesterRig(0.0)
    private val t0 = System.nanoTime()
    private var lastFrameNs = 0L
    private var dragDx = 0f
    private var dragDy = 0f
    private var touch: Pair<Float, Float>? = null   // view px, y-down
    private var running = false
    private var frames = 0; private var stepNs = 0L; private var drawNs = 0L; private var statT0 = System.nanoTime()

    /** px per rig unit; default makes the character about 120dp tall (rig is ~125 units tall) */
    var zoom: Float
        get() = rig.zoom
        set(v) { rig.zoom = v; requestLayout(); invalidate() }

    var state: String = "sleep"
        set(v) { field = v; rig.request(v, tool) }
    var tool: String = ""
        set(v) { field = v; rig.request(state, v) }
    /** 60 or 120 */
    var maxFps: Int = 60
    var onSound: ((String) -> Unit)?
        get() = rig.onSound
        set(v) { rig.onSound = v }

    init {
        rig.zoom = 0.96f * resources.displayMetrics.density
    }

    /** Feed window movement (px, screen coordinates, y-down) so the hat tails swing like on a dragged Mac panel. */
    fun setDragDelta(dx: Float, dy: Float) { dragDx += dx; dragDy += dy }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (170f * rig.zoom).toInt()
        val h = (205f * rig.zoom + rig.groundPad).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> touch = Pair(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch = null
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        val nowNs = System.nanoTime()
        val time = (nowNs - t0) / 1e9
        val tc = touch
        val h = height.toFloat()
        rig.step(time, Pt(dragDx, -dragDy), if (tc != null) Pt(tc.first, h - tc.second) else null, width.toFloat(), h)
        dragDx = 0f; dragDy = 0f
        val t1 = System.nanoTime()
        rig.draw(canvas, width.toFloat(), h)
        val t2 = System.nanoTime()
        stepNs += t1 - nowNs; drawNs += t2 - t1
        if (++frames >= 120) {
            val secs = (t2 - statT0) / 1e9
            android.util.Log.i("Jester", "avg step %.3f ms, draw %.3f ms, %.1f fps, bakes %d, key %s".format(stepNs / frames / 1e6, drawNs / frames / 1e6, frames / secs, bakeCount, rig.key)); bakeCount = 0
            frames = 0; stepNs = 0; drawNs = 0; statT0 = t2
        }
        lastFrameNs = nowNs
    }

    /**
     * Timer-driven loop (Handler, not Choreographer): Samsung One UI stops Choreographer/animation callbacks for apps in
     * the background state ("CoreRune ... stop animation in background states"), and an overlay-only app is background.
     * invalidate() from a plain Handler keeps working, as the pixel RobotView's postInvalidateDelayed always did.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (isShown) invalidate()
            val interval = 1000L / fps()
            postDelayed(this, interval - (android.os.SystemClock.uptimeMillis() % interval).coerceAtMost(interval - 1))
        }
    }

    // phone battery: 30 fps when calm (breathing/sway reads fine), maxFps only for fast moves, 15 asleep
    private fun fps(): Int =
        if (rig.key == "sleep") 15 else if (rig.wantsHighFPS) maxFps else minOf(30, maxFps)

    private fun start() {
        if (running) return
        running = true; lastFrameNs = 0L
        post(tick)
    }

    private fun stop() { running = false; removeCallbacks(tick) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { stop(); start() } else if (visibility != VISIBLE) stop()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) { stop(); start() } else if (visibility != VISIBLE) stop()
    }
}
