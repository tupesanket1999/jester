package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.Looks
import io.github.tupesanket1999.jester.logic.Tokens
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The current outfit's colors as Compose colors. */
class J(t: Tokens) {
    val dark = t.dark
    val bg = Color(t.bg); val surface = Color(t.surface); val line = Color(t.line); val hair = Color(t.hair)
    val text = Color(t.text); val muted = Color(t.muted); val muted2 = Color(t.muted2)
    val accent = Color(t.accent); val onAccent = Color(t.onAccent); val accentSoft = Color(t.accentSoft); val accentInk = Color(t.accentInk)
    val hot = Color(t.hot); val onHot = Color(t.onHot); val hotSurface = Color(t.hotSurface); val hotLine = Color(t.hotLine); val hotInk = Color(t.hotInk)
    val ok = Color(t.ok); val hatL = Color(t.hatL); val hatR = Color(t.hatR); val bell = Color(t.bell); val face = Color(t.face); val cheek = Color(t.cheek)
    val nav = Color(t.nav); val toastBg = Color(t.toastBg); val toastText = Color(t.toastText); val toastAction = Color(t.toastAction)
    val alarmBg = Color(t.alarmBg); val alarmText = Color(t.alarmText); val alarmInk = Color(t.alarmInk); val alarmLine = Color(t.alarmLine)
    val alarmBtn = Color(t.alarmBtn); val alarmBtnText = Color(t.alarmBtnText)
    val trackOff = Color(t.trackOff); val knobOff = Color(t.knobOff)

    fun scheme(): ColorScheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, secondary = hot, onSecondary = onHot, background = bg, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = surface, onSurfaceVariant = muted, outline = line, error = hot,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, secondary = hot, onSecondary = onHot, background = bg, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = surface, onSurfaceVariant = muted, outline = line, error = hot,
    )
}

val LocalJ = staticCompositionLocalOf { J(Looks.get("circus")) }
/** false when the system "Remove animations" setting is on: every loop/entrance becomes static. */
val LocalMotion = staticCompositionLocalOf { true }

fun motionOn(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) != 0f

fun currentLook(app: JesterApp): String = app.prefs.currentTheme(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

/** Jester theme: outfit colors + Material scheme + motion flag. Recomposes when look prefs change. */
@Composable
fun JesterTheme(override: String? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val app = JesterApp.get(ctx)
    val v by app.lookVersion.collectAsState()
    val id = override ?: remember(v) { currentLook(app) }
    val j = remember(id) { J(Looks.get(id)) }
    val motion = remember { motionOn(ctx) }
    CompositionLocalProvider(LocalJ provides j, LocalMotion provides motion) {
        MaterialTheme(colorScheme = j.scheme(), content = content)
    }
}

// ---------------------------------------------------------------- motion helpers

/** Entrance: fade + rise, staggered by [delayMs]. */
fun Modifier.rise(delayMs: Int = 0, dy: Dp = 16.dp): Modifier = composed {
    if (!LocalMotion.current) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(delayMs.toLong()); a.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { dy.toPx() }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * px }
}

/** Entrance: springy pop from small. */
fun Modifier.popIn(delayMs: Int = 0): Modifier = composed {
    if (!LocalMotion.current) return@composed this
    val a = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        a.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer { scaleX = a.value; scaleY = a.value; alpha = ((a.value - 0.3f) / 0.7f).coerceIn(0f, 1f) }
}

/** Looping "pulse": alpha + scale breathing (live dots, glows). */
@Composable
fun pulse(periodMs: Int = 1600): Float {
    if (!LocalMotion.current) return 0.5f
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse), label = "p").value
}

/** Looping 0..1 (no reverse). */
@Composable
fun loop(periodMs: Int, delayMs: Int = 0): Float {
    if (!LocalMotion.current) return 0f
    val t = rememberInfiniteTransition(label = "loop")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, delayMs, LinearEasing), RepeatMode.Restart), label = "l").value
}

/** The urgent card wiggle: still most of the time, a quick shimmy every [periodMs]. Degrees. */
@Composable
fun wiggle(periodMs: Int = 3400, amp: Float = 1.5f): Float {
    if (!LocalMotion.current) return 0f
    val t = rememberInfiniteTransition(label = "wiggle")
    return t.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = periodMs
        0f at (periodMs * 0.84f).toInt()
        -amp at (periodMs * 0.87f).toInt()
        amp at (periodMs * 0.90f).toInt()
        -amp * 0.66f at (periodMs * 0.93f).toInt()
        amp * 0.66f at (periodMs * 0.96f).toInt()
    }), label = "w").value
}

// ---------------------------------------------------------------- Jester (pixel art from the mockup)

/** One pixel rect: x, y, w, h, color role. */
private data class Px(val x: Int, val y: Int, val w: Int, val h: Int, val role: Char)

// roles: L hatL, R hatR, B bell, F face, E eye, W eye-shine, C cheek, M mouth
private val HAT_L = listOf(Px(2, 0, 1, 1, 'B'), Px(2, 1, 1, 1, 'L'), Px(2, 2, 2, 1, 'L'))
private val HAT_R = listOf(Px(11, 0, 1, 1, 'B'), Px(11, 1, 1, 1, 'R'), Px(10, 2, 2, 1, 'R'))
private val BODY = listOf(
    Px(3, 3, 2, 1, 'L'), Px(9, 3, 2, 1, 'R'), Px(3, 4, 4, 1, 'L'), Px(7, 4, 4, 1, 'R'),
    Px(2, 5, 10, 1, 'B'), Px(2, 6, 10, 6, 'F'),
    Px(3, 9, 1, 1, 'C'), Px(10, 9, 1, 1, 'C'),
    Px(4, 10, 1, 1, 'M'), Px(9, 10, 1, 1, 'M'), Px(5, 11, 4, 1, 'M'),
    Px(3, 12, 8, 2, 'L'), Px(4, 12, 1, 1, 'B'), Px(6, 12, 1, 1, 'B'), Px(8, 12, 1, 1, 'B'),
)
private val EYES = listOf(Px(3, 7, 2, 2, 'E'), Px(9, 7, 2, 2, 'E'), Px(4, 7, 1, 1, 'W'), Px(10, 7, 1, 1, 'W'))

/**
 * Jester as drawn in the mockup: 14×14 pixels, hat cones jingle, eyes blink, the whole body bobs.
 * [shake] = alarm mode (fast jingle + side-to-side shake). [spin] in degrees (outfit change).
 */
@Composable
fun JesterFace(size: Dp, modifier: Modifier = Modifier, shake: Boolean = false, spin: Float = 0f, scale: Float = 1f) {
    val j = LocalJ.current
    val motion = LocalMotion.current
    val bob = if (motion) loop(2400) else 0f
    val jingle = if (motion) loop(if (shake) 350 else 1800) else 0f
    val blinkT = if (motion) loop(4200) else 0f
    val shakeT = if (motion && shake) loop(1100) else 0f
    Canvas(modifier.size(size)) {
        val u = this.size.width / 14f
        val bobY = if (motion) (-sin(bob * 2 * PI).toFloat() * 0.3f * u) else 0f
        val shakeX = if (shake) (sin(shakeT * 2 * PI * 5).toFloat() * (if (shakeT < 0.5f) 0.35f else 0f) * u) else 0f
        val blink = blinkT in 0.92f..0.96f
        val ang = sin(jingle * 2 * PI).toFloat() * (if (shake) 24f else 16f)
        fun color(r: Char) = when (r) {
            'L' -> j.hatL; 'R' -> j.hatR; 'B' -> j.bell; 'F' -> j.face; 'C' -> j.cheek
            'W' -> Color.White; else -> Color(0xFF14151D)
        }
        fun DrawScope.px(p: Px, squash: Boolean = false) {
            val h = if (squash) p.h * u * 0.15f else p.h * u
            val top = if (squash) p.y * u + (p.h * u - h) / 2 else p.y * u
            drawRect(color(p.role), Offset(p.x * u, top), Size(p.w * u + 0.5f, h + 0.5f))
        }
        withTransform({
            translate(shakeX, bobY)
            rotate(spin, Offset(this@Canvas.size.width / 2, this@Canvas.size.height / 2))
            scale(scale, scale, Offset(this@Canvas.size.width / 2, this@Canvas.size.height / 2))
        }) {
            rotate(ang, Offset(3f * u, 3f * u)) { HAT_L.forEach { px(it) } }
            rotate(-ang, Offset(11f * u, 3f * u)) { HAT_R.forEach { px(it) } }
            BODY.forEach { px(it) }
            EYES.forEach { if (!(blink && it.role == 'W')) px(it, squash = blink) }
        }
    }
}

// ---------------------------------------------------------------- confetti

/** A one-shot burst; restart it by changing [key] (a counter). Origin is the box center offset by [origin]. */
@Composable
fun Confetti(key: Int, modifier: Modifier = Modifier, count: Int = 26, origin: Offset = Offset.Zero) {
    if (key <= 0 || !LocalMotion.current) return
    val j = LocalJ.current
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val colors = listOf(j.accent, j.hot, j.bell, j.ok, j.hatR, j.cheek)
    Canvas(modifier.fillMaxSize()) {
        if (a.value >= 1f) return@Canvas
        val c = Offset(size.width / 2, size.height / 2) + origin
        for (i in 0 until count) {
            val ang = i.toFloat() / count * 2 * PI
            val dist = (90 + (i * 37) % 80) * density * a.value
            val x = c.x + cos(ang).toFloat() * dist
            val y = c.y + sin(ang).toFloat() * dist - 50 * density * a.value + 120 * density * a.value * a.value
            val s = (6 + (i % 4) * 2) * density
            rotate(a.value * ((i * 67) % 540 - 270), Offset(x, y)) {
                drawRect(colors[i % colors.size].copy(alpha = 1f - a.value), Offset(x - s / 2, y - s / 2), Size(s, s))
            }
        }
    }
}

// ---------------------------------------------------------------- switch, dot, icons

@Composable
fun JSwitch(on: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val j = LocalJ.current
    val x by animateDpAsState(if (on) 18.dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(
        Modifier.size(52.dp, 44.dp).clickable(onClickLabel = label) { onChange(!on) }
            .switchSemantics(on, label),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp, 26.dp).background(if (on) j.accent else j.trackOff, RoundedCornerShape(50)).padding(3.dp)) {
            Box(Modifier.offset(x = x).size(20.dp).background(if (on) j.onAccent else j.knobOff, CircleShape))
        }
    }
}

/** Switch semantics (role + state) for TalkBack. */
fun Modifier.switchSemantics(on: Boolean, label: String): Modifier = this.semantics(mergeDescendants = true) {
    role = Role.Switch
    stateDescription = if (on) "On" else "Off"
    contentDescription = label
}

@Composable
fun LiveDot(color: Color, size: Dp = 8.dp, delayMs: Int = 0) {
    val p = pulse(800 + delayMs)
    Box(Modifier.size(size).graphicsLayer { alpha = 0.4f + 0.6f * (1 - p); scaleX = 0.7f + 0.3f * (1 - p); scaleY = scaleX }.background(color, CircleShape))
}

/** Stroke icons from the mockup's SVG paths (24×24 viewport). No icon library needed. */
object JIcons {
    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    val Today = stroke("today", "M6 4h12a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z", "M3 9h18M8 2v4M16 2v4")
    val Chat = stroke("chat", "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z")
    val Me = stroke("me", "M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M4 21a8 8 0 0 1 16 0")
    val Mic = stroke("mic", "M12 2a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3z", "M5 10a7 7 0 0 0 14 0", "M12 17v4")
    val Send = stroke("send", "M5 12h14M13 6l6 6-6 6")
    val Bell = stroke("bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Check = stroke("check", "M5 12.5l4 4L19 7")
    val Camera = stroke("camera", "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M15.5 13.5a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z")
    val Speaker = stroke("speaker", "M11 5L6 9H3v6h3l5 4V5z", "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 5.5a9 9 0 0 1 0 13")
    val Gear = stroke("gear", "M12 15a3 3 0 1 0 0-6a3 3 0 0 0 0 6z", "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3a1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8a1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
}
