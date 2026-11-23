package io.github.tupesanket1999.jester.overlay

import io.github.tupesanket1999.jester.Prefs
import io.github.tupesanket1999.jester.RingItem
import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.bot.BotPack
import io.github.tupesanket1999.jester.jester.JesterAudio
import io.github.tupesanket1999.jester.jester.JesterView
import io.github.tupesanket1999.jester.logic.VoicePolicy
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating robot: a small TYPE_APPLICATION_OVERLAY window (robot + speech bubble) that can be dragged and snaps
 * to the nearest screen edge. A second, invisible 1-px window watches the system bars to auto-hide in full-screen apps.
 */
class Overlay(
    private val ctx: Context,
    private val prefs: Prefs,
    initialPack: BotPack,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private var pack: BotPack = initialPack
    private val audio = JesterAudio()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var jester: JesterView? = null
    private var robot: RobotView? = null
    /** The active body: a [JesterView] when the selected bot is the jester, else the pixel [RobotView]. */
    private var body: View = makeBody(initialPack)
    private var state = "idle"
    private val bubble = TextView(ctx).apply {
        setTextColor(0xFF120B0D.toInt()); textSize = 13f
        maxWidth = (220 * dp).toInt(); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xF2FFECE8.toInt()); setStroke((2 * dp).toInt(), initialPack.accent) }
        visibility = View.GONE
    }
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2 * dp).toInt() })
        addView(body)
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.robotX; y = prefs.robotY }

    // full-screen detector: invisible, untouchable, full height
    private val probe = View(ctx)
    private val probeParams = WindowManager.LayoutParams(
        1, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSPARENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var attached = false
    private var fullscreen = false
    private var wantShown = false

    fun canDraw() = Settings.canDrawOverlays(ctx)

    private fun makeBody(p: BotPack): View {
        jester = null; robot = null
        return if (p.isJester) JesterView(ctx).also {
            jester = it
            it.onSound = { cue ->
                if (sfxAllowed()) {
                    audio.volume = prefs.sfxVolume
                    audio.play(cue, gain = if (cue == "tinkle") 0.6f else 1f, minGapMs = if (cue == "tinkle") 700 else 120)
                }
            }
            it.maxFps = prefs.maxFps
            it.eco = prefs.eco
        } else RobotView(ctx, p).also { robot = it }
    }

    private fun applyState() {
        jester?.state = state
        robot?.state = state
    }

    /** Swap the bot: a different pack, or the jester <-> pixel switch (replaces the body view in place). */
    fun setPack(p: BotPack) {
        val wasJester = pack.isJester
        pack = p
        (bubble.background as? GradientDrawable)?.setStroke((2 * dp).toInt(), p.accent)
        if (wasJester == p.isJester && !p.isJester) { robot?.pack = p; return }
        if (wasJester == p.isJester) return
        root.removeView(body)
        body = makeBody(p)
        root.addView(body)
        applyState()
        applyVisibility()
        if (attached) update()
    }

    /** Re-read settings that can change while running (frame cap). */
    fun applySettings() { jester?.maxFps = prefs.maxFps; jester?.eco = prefs.eco }

    fun release() { mainHandler.removeCallbacksAndMessages(null); audio.release() }

    private fun bodyWidth(): Int = body.width.takeIf { it > 0 } ?: body.measuredWidth.takeIf { it > 0 }
        ?: robot?.let { pack.width * it.px } ?: (170f * 0.96f * dp).toInt()

    private fun bodyHeight(): Int = body.height.takeIf { it > 0 } ?: body.measuredHeight.takeIf { it > 0 }
        ?: robot?.let { pack.height * it.px } ?: (205f * 0.96f * dp + 10f + 12f * 0.96f * dp).toInt()

    private fun sfxAllowed(): Boolean {
        if (!prefs.sfx || !attached || root.visibility != View.VISIBLE) return false
        val c = Calendar.getInstance()
        if (VoicePolicy.inQuiet(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), prefs.quietStart, prefs.quietEnd)) return false
        val am = ctx.getSystemService(AudioManager::class.java)
        return am == null || am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    /** Show [text] in the bubble; a NEW text gets a short babble unless the voice is already speaking it. */
    private fun setBubbleText(text: String) {
        val isNew = bubble.text.toString() != text || bubble.visibility != View.VISIBLE
        bubble.text = text
        bubble.visibility = View.VISIBLE
        if (!isNew || jester == null) return
        mainHandler.postDelayed({
            if (bubble.visibility == View.VISIBLE && prefs.babble && sfxAllowed() && !JesterApp.get(ctx).voice.isSpeaking) {
                audio.volume = prefs.sfxVolume
                audio.babble(text.lineSequence().first())
            }
        }, 250)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        if (attached || !canDraw()) return
        if (params.x < 0) params.x = screenWidth() - bodyWidth()
        params.x = params.x.coerceIn(0, maxOf(0, screenWidth() - bodyWidth()))
        params.y = params.y.coerceIn(0, maxOf(0, screenHeight() - bodyHeight()))
        val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
            override fun onLongPress(e: MotionEvent) { if (!dragging) onLongPress() }
        })
        root.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0; var downRawX = 0f; var downRawY = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downRawX = e.rawX; downRawY = e.rawY; dragging = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                        if (!dragging && (abs(dx) > 12 * dp || abs(dy) > 12 * dp)) {
                            dragging = true
                            // cancel the pending tap / long-press in the detector
                            MotionEvent.obtain(e).also { it.action = MotionEvent.ACTION_CANCEL; gestures.onTouchEvent(it); it.recycle() }
                        }
                        if (dragging) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); update() }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { snapToEdge(); return true }
                }
                return if (dragging) true else gestures.onTouchEvent(e)
            }
        })
        lastX = params.x; lastY = params.y
        wm.addView(root, params)
        jester?.let { j -> j.post { android.util.Log.i("Jester", "jesterView.isHardwareAccelerated=${j.isHardwareAccelerated} size=${j.width}x${j.height}") } }
        try { wm.addView(probe, probeParams) } catch (e: Exception) { /* detector is best effort */ }
        watchFullscreen()
        attached = true
        wantShown = true
        applyVisibility()
    }

    private var dragging = false

    fun detach() {
        if (!attached) return
        runCatching { wm.removeView(root) }
        runCatching { wm.removeView(probe) }
        attached = false
    }

    /** Show/hide without tearing the window down (hide-for-1-hour, hide, full-screen). */
    fun setShown(shown: Boolean) { wantShown = shown; applyVisibility() }

    private fun applyVisibility() {
        val visible = wantShown && !(fullscreen && prefs.autoHideFullscreen)
        root.visibility = if (visible) View.VISIBLE else View.GONE
        robot?.running = visible
    }

    private var lastRinging: List<RingItem> = emptyList()
    private var quip: String? = null

    /** A one-off line (battery humour…); null goes back to the ringing bubble. */
    fun showQuip(text: String?) {
        quip = text
        if (text == null) { showRinging(lastRinging); return }
        setBubbleText(text)
        state = "sleep"; applyState()
        if (attached) update()
    }

    fun showRinging(items: List<RingItem>) {
        lastRinging = items
        if (quip != null && items.isEmpty()) return
        if (items.isEmpty()) {
            bubble.visibility = View.GONE
            state = "idle"
        } else {
            setBubbleText(items.joinToString("\n") { "⏰ " + it.title })
            state = "remind"
        }
        applyState()
        if (attached) update()
    }

    private fun watchFullscreen() {
        probe.setOnApplyWindowInsetsListener { _, insets ->
            fullscreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) !insets.isVisible(WindowInsets.Type.statusBars())
            else @Suppress("DEPRECATION") (insets.systemWindowInsetTop == 0)
            applyVisibility()
            insets
        }
    }

    private fun snapToEdge() {
        val w = root.width.takeIf { it > 0 } ?: bodyWidth()
        val target = if (params.x + w / 2 < screenWidth() / 2) 0 else screenWidth() - w
        params.y = params.y.coerceIn(0, maxOf(0, screenHeight() - root.height))
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { params.x = it.animatedValue as Int; update() }
            start()
        }
        prefs.robotX = target; prefs.robotY = params.y
    }

    private var lastX = 0
    private var lastY = 0

    private fun update() {
        if (!attached) return
        jester?.setDragDelta((params.x - lastX).toFloat(), (params.y - lastY).toFloat())
        lastX = params.x; lastY = params.y
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun screenWidth(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width()
        else ctx.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height()
        else ctx.resources.displayMetrics.heightPixels
}
