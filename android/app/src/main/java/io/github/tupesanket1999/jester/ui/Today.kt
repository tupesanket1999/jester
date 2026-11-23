package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.RingItem
import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.Looks
import io.github.tupesanket1999.jester.logic.QuickParse
import io.github.tupesanket1999.jester.logic.ReplyParser
import io.github.tupesanket1999.jester.reminders.Reminders
import io.github.tupesanket1999.jester.reminders.TaskLite
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A bottom toast with an optional Undo. */
data class ToastMsg(val text: String, val undo: (() -> Unit)? = null, val id: Long = System.nanoTime())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(app: JesterApp, quick: MutableState<String>, onMic: () -> Unit, onCamera: () -> Unit, onNav: (String) -> Unit) {
    val j = LocalJ.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val tasks by app.tasks.collectAsState()
    val ringing by app.ringing.collectAsState()
    val look by app.lookVersion.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    // optimistic local state: done/snoozed items change instantly; the hub write is committed after the Undo window
    var localDone by remember { mutableStateOf(setOf<String>()) }
    var localHidden by remember { mutableStateOf(setOf<String>()) }
    var toast by remember { mutableStateOf<ToastMsg?>(null) }
    var burst by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) } // long-pressed row -> reschedule chips
    var showLater by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    val jobs = remember { HashMap<String, Job>() }

    fun showToast(t: ToastMsg) { toast = t; scope.launch { delay(3800); if (toast?.id == t.id) toast = null } }

    fun markDone(id: String, title: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        localDone = localDone + id; burst++
        jobs[id]?.cancel()
        jobs[id] = scope.launch { delay(3500); app.done(id); localDone = localDone - id }
        showToast(ToastMsg("Done: $title", undo = { jobs.remove(id)?.cancel(); localDone = localDone - id }))
    }

    fun snooze(id: String, title: String, minutes: Int, label: String) {
        localHidden = localHidden + id
        jobs[id]?.cancel()
        jobs[id] = scope.launch { delay(3500); app.snooze(id, minutes); localHidden = localHidden - id }
        showToast(ToastMsg("$label: $title", undo = { jobs.remove(id)?.cancel(); localHidden = localHidden - id }))
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val todayStr = today.toString()
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    fun dueAt(t: TaskLite): Long? = t.due?.let { Reminders.dueToEpochMs(it, zone) }
    val visible = tasks.filter { it.id !in localHidden }
    // due today (open or done), plus anything overdue and still open
    val todays = visible.filter { t -> val d = t.due; d != null && (d.startsWith(todayStr) || (d < todayStr && !t.done)) }
        .sortedBy { it.due }
    val later = visible.filter { !it.done && it.id !in localDone && (it.due == null || it.due > "$todayStr 99") }
    val doneToday = visible.filter { (it.done || it.id in localDone) && it.due?.startsWith(todayStr) == true }
    val ringingVisible = ringing.filter { it.id !in localDone && it.id !in localHidden }
    val openToday = todays.count { !it.done && it.id !in localDone }
    val left = openToday + ringingVisible.count { r -> todays.none { it.id == r.id } }
    val next = visible.filter { !it.done && it.id !in localDone }.mapNotNull { t -> dueAt(t)?.let { t to it } }
        .filter { it.second > now }.minByOrNull { it.second }

    Box(Modifier.fillMaxSize().background(j.bg)) {
        Column(Modifier.fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp).rise(0), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())), color = j.muted, fontSize = 13.sp)
                    Text("Today", color = j.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                val mac = app.prefs.macState.ifEmpty { "unknown" }
                if (app.prefs.configured) Row(Modifier.background(j.surface, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(if (mac in setOf("online", "working", "sleep_working")) j.ok else j.muted2)
                    Spacer(Modifier.width(8.dp))
                    Text("Mac · ${mac.replace('_', ' ')}", color = j.muted, fontSize = 13.sp)
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Hero(app, left, next?.first, next?.second, now, look)

                if (ringingVisible.isNotEmpty()) Column(Modifier.rise(120), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("RINGING", j.hotInk)
                    ringingVisible.forEach { r -> key(r.id) { RingCard(r, onDone = { markDone(r.id, r.title) }, onSnooze = { snooze(r.id, r.title, 15, "Snoozed 15 min") }) } }
                }

                Column(Modifier.rise(180), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("TODAY", j.muted, Modifier.weight(1f))
                        Text("swipe right = done · left = snooze", color = j.muted2, fontSize = 12.sp)
                    }
                    Column(Modifier.fillMaxWidth().background(j.surface, RoundedCornerShape(20.dp))) {
                        if (todays.isEmpty()) Text("Nothing due today. Suspiciously calm.", color = j.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                        todays.forEachIndexed { i, t ->
                            key(t.id) {
                                val isDone = t.done || t.id in localDone
                                if (i > 0) HorizontalDivider(color = j.hair)
                                val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                                    when (v) {
                                        SwipeToDismissBoxValue.StartToEnd -> if (!isDone) markDone(t.id, t.title)
                                        SwipeToDismissBoxValue.EndToStart -> if (!isDone) snooze(t.id, t.title, 15, "Snoozed 15 min")
                                        else -> {}
                                    }
                                    false
                                })
                                SwipeToDismissBox(state, backgroundContent = {
                                    val toDone = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                    Box(Modifier.fillMaxSize().background(if (toDone) j.accent else j.hot).padding(horizontal = 20.dp),
                                        contentAlignment = if (toDone) Alignment.CenterStart else Alignment.CenterEnd) {
                                        Text(if (toDone) "Done" else "Snooze 15m", color = if (toDone) j.onAccent else j.onHot, fontWeight = FontWeight.Bold)
                                    }
                                }) {
                                    TaskRow(t, isDone, i, now,
                                        onToggle = { if (!isDone) markDone(t.id, t.title) else if (t.id in localDone) { jobs.remove(t.id)?.cancel(); localDone = localDone - t.id } },
                                        onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); expanded = if (expanded == t.id) null else t.id })
                                }
                                AnimatedVisibility(expanded == t.id) {
                                    Reschedule { mins, label -> expanded = null; snooze(t.id, t.title, mins, label) }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.rise(300), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CountButton("Later", later.size, Modifier.weight(1f)) { showLater = !showLater }
                    CountButton("Done", doneToday.size, Modifier.weight(1f)) { showDone = !showDone }
                }
                AnimatedVisibility(showLater) {
                    SimpleList(later.sortedBy { it.due ?: "9999" }.take(40)) { t -> (t.due?.let { prettyDue(it, today) } ?: "no date") }
                }
                AnimatedVisibility(showDone) { SimpleList(doneToday) { "done" } }
            }

            QuickAdd(app, quick, onMic, onCamera) { text ->
                app.capture(text); quick.value = ""; burst++
                showToast(ToastMsg("Added: " + QuickParse.parse(text, Instant.now(), zone).title))
            }
        }

        // toast
        AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
            enter = slideInVertically(spring(dampingRatio = 0.6f)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val t = toast
            Row(Modifier.fillMaxWidth().height(48.dp).background(j.toastBg, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(t?.text ?: "", color = j.toastText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t?.undo != null) TextButton(onClick = { t.undo.invoke(); toast = null }) { Text("Undo", color = j.toastAction, fontWeight = FontWeight.Bold) }
            }
        }
        Confetti(burst, origin = Offset(0f, -120f))
    }
}

@Composable
private fun SectionLabel(text: String, color: Color, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = modifier)

private fun prettyDue(due: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return due
    val days = Duration.between(today.atStartOfDay(), d.atStartOfDay()).toDays()
    val day = when (days) { 0L -> "Today"; 1L -> "Tomorrow"; in 2..6 -> d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }; else -> d.format(DateTimeFormatter.ofPattern("d MMM")) }
    return "$day ${due.drop(11)}"
}

@Composable
private fun Hero(app: JesterApp, left: Int, next: TaskLite?, nextAt: Long?, now: Long, @Suppress("UNUSED_PARAMETER") look: Int) {
    val j = LocalJ.current
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    val tone = app.prefs.tone
    Row(Modifier.fillMaxWidth().rise(60).background(j.surface, RoundedCornerShape(22.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).background(j.accentSoft, RoundedCornerShape(20.dp)).clickable(onClickLabel = "Change Jester's outfit") {
            val nextLook = Looks.next(currentLook(app))
            app.prefs.autoTheme = false; app.prefs.theme = nextLook
            app.lookVersion.value++
            scope.launch { spin.snapTo(0f); spin.animateTo(360f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        }, contentAlignment = Alignment.Center) {
            val s = 1f + 0.3f * kotlin.math.sin(Math.toRadians(spin.value.toDouble() / 2)).toFloat()
            JesterFace(60.dp, spin = spin.value, scale = s)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(Looks.quip(tone, left), color = j.text, fontSize = 15.sp, lineHeight = 20.sp)
            Text("MOOD: ${Looks.mood(left)} · TAP ME", color = j.accentInk, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Countdown(next, nextAt, now)
    }
}

@Composable
private fun Countdown(next: TaskLite?, nextAt: Long?, now: Long) {
    val j = LocalJ.current
    val remaining = nextAt?.let { (it - now).coerceAtLeast(0) }
    val frac = remaining?.let { (it / 3_600_000f).coerceIn(0f, 1f) } ?: 1f
    val shown by animateFloatAsState(frac, tween(900, easing = FastOutSlowInEasing), label = "ring")
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val glow = pulse(1300)
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) {
            val sw = 6.dp.toPx()
            drawCircle(j.accent.copy(alpha = 0.25f * (1 - glow)), radius = size.minDimension / 2 + 4.dp.toPx() * glow)
            drawArc(j.line, 0f, 360f, false, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw))
            drawArc(j.accent, -90f, 360f * shown * draw.value, false, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val txt = when {
                remaining == null -> "free"
                remaining < 3_600_000 -> "%d:%02d".format(remaining / 60_000, (remaining / 1000) % 60)
                else -> "${remaining / 3_600_000}h ${(remaining / 60_000) % 60}m"
            }
            Text(txt, color = j.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(next?.title?.take(9) ?: "", color = j.muted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RingCard(r: RingItem, onDone: () -> Unit, onSnooze: () -> Unit) {
    val j = LocalJ.current
    val w = wiggle()
    val g = pulse(1100)
    val bell = wiggle(2400, 22f)
    Column(Modifier.fillMaxWidth().graphicsLayer { rotationZ = w }
        .border(BorderStroke(1.dp + 5.dp * (1 - g) * 0.4f, j.hot.copy(alpha = 0.35f + 0.65f * g)), RoundedCornerShape(20.dp))
        .background(j.hotSurface, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(JIcons.Bell, null, tint = j.hot, modifier = Modifier.size(22.dp).rotate(bell))
            Column(Modifier.padding(start = 12.dp)) {
                Text(r.title, color = j.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val ping = if (r.level >= 2) " · ${io.github.tupesanket1999.jester.logic.NotifText.ordinal(r.level)} ping" else ""
                Text((r.due?.drop(11)?.let { "Due $it" } ?: "Ringing") + ping, color = j.hotInk, fontSize = 13.sp)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onSnooze, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, j.hotLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = j.hotInk)) { Text("Snooze 15m", fontWeight = FontWeight.SemiBold) }
            Button(onDone, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = j.hot, contentColor = j.onHot)) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(t: TaskLite, done: Boolean, index: Int, now: Long, onToggle: () -> Unit, onLong: () -> Unit) {
    val j = LocalJ.current
    val pop by animateFloatAsState(if (done) 1f else 0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "check")
    Row(Modifier.fillMaxWidth().background(j.surface).combinedClickable(onClick = onToggle, onLongClick = onLong)
        .padding(horizontal = 14.dp, vertical = 12.dp).rise(250 + index * 70), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(21.dp).border(2.dp, j.muted2, CircleShape))
            Box(Modifier.size(24.dp).graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) }.background(j.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(JIcons.Check, null, tint = j.onAccent, modifier = Modifier.size(16.dp))
            }
        }
        Text(t.due?.drop(11) ?: "", color = if (done) j.muted2 else j.accentInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).width(46.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (done) j.muted2 else j.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                val at = t.due?.let { Reminders.dueToEpochMs(it, ZoneId.systemDefault()) }
                if (!done && at != null) {
                    val m = (at - now) / 60_000
                    append(if (m >= 0) (if (m < 60) "in $m min" else "in ${m / 60} h") else "${-m} min late".takeIf { -m < 120 } ?: "overdue")
                }
                if (t.priority == "urgent") append(if (isEmpty()) "urgent" else " · urgent")
                else if (t.priority == "important") append(if (isEmpty()) "important" else " · important")
            }
            if (meta.isNotEmpty()) Text(meta, color = j.muted2, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reschedule(onPick: (Int, String) -> Unit) {
    val j = LocalJ.current
    val now = LocalDateTime.now()
    val sat = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atTime(10, 0)
    val opts = listOf(
        "+1 hour" to 60,
        "Tonight 9 PM" to ReplyParser.until(now, 21, 0),
        "Tomorrow 9 AM" to Duration.between(now, now.toLocalDate().plusDays(1).atTime(9, 0)).toMinutes().toInt(),
        "Weekend" to Duration.between(now, sat).toMinutes().toInt(),
    )
    FlowRow(Modifier.fillMaxWidth().background(j.accentSoft).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEachIndexed { i, (label, mins) ->
            Text(label, color = j.accentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.popIn(i * 50).background(j.surface, RoundedCornerShape(50)).clickable { onPick(mins.coerceIn(1, 7 * 1440), "Moved to $label") }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun CountButton(label: String, n: Int, modifier: Modifier, onClick: () -> Unit) {
    val j = LocalJ.current
    Row(modifier.height(52.dp).background(j.surface, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = j.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("$n", color = j.muted2, fontSize = 15.sp)
    }
}

@Composable
private fun SimpleList(items: List<TaskLite>, meta: (TaskLite) -> String) {
    val j = LocalJ.current
    Column(Modifier.fillMaxWidth().background(j.surface, RoundedCornerShape(16.dp)).padding(vertical = 4.dp)) {
        if (items.isEmpty()) Text("Nothing here.", color = j.muted, modifier = Modifier.padding(14.dp))
        items.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).rise(i * 40)) {
                Text(t.title, color = j.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta(t), color = j.muted2, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAdd(app: JesterApp, quick: MutableState<String>, onMic: () -> Unit, onCamera: () -> Unit, onSave: (String) -> Unit) {
    val j = LocalJ.current
    val chips = remember(quick.value) {
        if (quick.value.isBlank()) emptyList() else QuickParse.chips(QuickParse.parse(quick.value, Instant.now(), ZoneId.systemDefault()), Instant.now(), ZoneId.systemDefault())
    }
    Column(Modifier.fillMaxWidth().background(j.bg).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        HorizontalDivider(color = j.hair, modifier = Modifier.padding(bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                quick.value, { quick.value = it.take(1000) },
                Modifier.weight(1f).heightIn(min = 52.dp),
                placeholder = { Text("call mom tomorrow 6pm !", color = j.muted2) },
                shape = RoundedCornerShape(16.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (quick.value.isNotBlank()) onSave(quick.value.trim()) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = j.accent, unfocusedBorderColor = j.line,
                    focusedContainerColor = j.surface, unfocusedContainerColor = j.surface, focusedTextColor = j.text, unfocusedTextColor = j.text, cursorColor = j.accent),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onCamera, Modifier.size(48.dp).background(j.surface, RoundedCornerShape(16.dp))) { Icon(JIcons.Camera, "Photo to todo", tint = j.accentInk) }
            Spacer(Modifier.width(6.dp))
            Box(contentAlignment = Alignment.Center) {
                val r = loop(2200)
                Box(Modifier.size(48.dp).graphicsLayer { val s = 0.7f + 1.0f * r; scaleX = s; scaleY = s; alpha = 0.8f * (1 - r) }.border(2.dp, j.accent, RoundedCornerShape(16.dp)))
                if (quick.value.isBlank()) IconButton(onMic, Modifier.size(48.dp).background(j.accent, RoundedCornerShape(16.dp))) { Icon(JIcons.Mic, "Speak", tint = j.onAccent) }
                else IconButton({ onSave(quick.value.trim()) }, Modifier.size(48.dp).background(j.accent, RoundedCornerShape(16.dp))) { Icon(JIcons.Send, "Add", tint = j.onAccent) }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 26.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (chips.isEmpty()) Text("Type a time and I'll show what I understood before saving.", color = j.muted2, fontSize = 12.sp)
            chips.forEach { c ->
                key(c.label) {
                    Text(c.label, color = if (c.hot) j.hotInk else j.accentInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.popIn().background(if (c.hot) j.hotSurface else j.accentSoft, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
        if (!app.prefs.configured) Text("No hub connected (optional: Me → Pairing). What you type is kept and sent when one is.", color = j.muted2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
