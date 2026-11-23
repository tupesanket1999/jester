package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.bot.BotRepo
import io.github.tupesanket1999.jester.logic.Looks
import io.github.tupesanket1999.jester.logic.VoiceMode
import io.github.tupesanket1999.jester.service.RobotService
import android.app.TimePickerDialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Trig(val key: String, val label: String, val hint: String, val ships: String?)

private val TRIGGERS = listOf(
    Trig("battery", "Battery humour", "Low battery = dramatic Jester", null),
    Trig("car", "Car Bluetooth", "Read out \"on the drive\" items", "v0.4"),
    Trig("charger", "Charger at night", "Shutdown review: done, carried, first up", "v0.4"),
    Trig("earbuds", "Earbuds in", "Speak due reminders", "v0.4"),
    Trig("unlock", "First unlock", "Morning brief card", "v0.4"),
    Trig("doom", "Doomscroll roast", "30 min in picked apps", "v0.4"),
    Trig("sms", "Bill SMS", "Due dates only, never amounts", "v0.5"),
    Trig("catcher", "Notification catcher", "Courier app → suggested reminder", "v0.5"),
    Trig("sleep", "Sleep-aware day", "Needs sleep data in Health Connect", "v0.5"),
)

@Composable
fun MeScreen(app: JesterApp, onNav: (String) -> Unit) {
    val j = LocalJ.current
    val ctx = LocalContext.current
    val p = app.prefs
    val scope = rememberCoroutineScope()
    // prefs are plain reads: bumping lookVersion (collected here) recomposes the screen after every change
    @Suppress("UNUSED_VARIABLE") val v = app.lookVersion.collectAsState().value
    fun bump() { app.lookVersion.value++ }

    Column(Modifier.fillMaxSize().background(j.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Me", color = j.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 20.dp).rise(0))

        // outfit
        Column(Modifier.rise(50), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(horizontal = 4.dp)) {
                Label("JESTER'S OUTFIT", Modifier.weight(1f))
                Text(Looks.NAMES[currentLook(app)] ?: "", color = j.accentInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth().background(j.surface, RoundedCornerShape(20.dp)).padding(vertical = 12.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Looks.ORDER.forEachIndexed { i, id ->
                    val t = J(Looks.get(id))
                    val sel = currentLook(app) == id
                    Box(Modifier.popIn(100 + i * 60).size(48.dp)
                        .border(if (sel) 2.dp else 1.dp, if (sel) j.text else j.line, CircleShape).padding(3.dp)
                        .background(t.bg, CircleShape).clickable(onClickLabel = Looks.NAMES[id]) { p.autoTheme = false; p.theme = id; bump() }) {
                        Box(Modifier.offset(6.dp, 8.dp).size(17.dp).background(t.accent, CircleShape))
                        Box(Modifier.align(Alignment.BottomEnd).offset((-6).dp, (-6).dp).size(15.dp).background(t.hot, CircleShape))
                        Box(Modifier.align(Alignment.TopEnd).offset((-9).dp, 8.dp).size(7.dp).background(t.bell, RoundedCornerShape(2.dp)))
                    }
                }
            }
            Card {
                SwitchRow("Auto outfit", "Mango mornings, Circus afternoons, Deep Sea after 9 PM", p.autoTheme) { p.autoTheme = it; bump() }
            }
        }

        // devices + AI
        Row(Modifier.rise(110), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val mac = p.macState.ifEmpty { "unknown" }
            Device("Mac", mac.replace('_', ' '), mac in setOf("online", "working", "sleep_working"), Modifier.weight(1f))
            Device("Phone", if (p.configured) "paired" else "no hub", p.configured, Modifier.weight(1f))
            Device("Watch", if (p.watchConfirmed) "via phone" else "not set", p.watchConfirmed, Modifier.weight(1f))
        }
        if (p.aiCap > 0) Card(Modifier.rise(160)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text("AI today", color = j.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${p.aiProvider.ifEmpty { "AI" }} · ${p.aiUsed} of ${p.aiCap}", color = j.muted, fontSize = 13.sp)
                }
                val frac = (p.aiUsed.coerceAtLeast(0).toFloat() / p.aiCap).coerceIn(0f, 1f)
                val a = remember { Animatable(0f) }
                LaunchedEffect(frac) { a.animateTo(frac, tween(1200, 300, FastOutSlowInEasing)) }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).background(j.line, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(a.value.coerceAtLeast(0.01f)).fillMaxHeight().background(j.accent, RoundedCornerShape(50)))
                }
            }
        }

        // voice + tone
        Column(Modifier.rise(220), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("VOICE & TONE", Modifier.padding(horizontal = 4.dp))
            Card {
                Column {
                    ValueRow("Jester's voice", p.voiceMode.label) {
                        p.voiceMode = VoiceMode.entries[(p.voiceMode.ordinal + 1) % VoiceMode.entries.size]; bump()
                    }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Quiet hours", "${hm(p.quietStart)} – ${hm(p.quietEnd)}") {
                        TimePickerDialog(ctx, { _, h, m ->
                            p.quietStart = h * 60 + m
                            TimePickerDialog(ctx, { _, h2, m2 -> p.quietEnd = h2 * 60 + m2; bump() }, p.quietEnd / 60, p.quietEnd % 60, true).apply { setTitle("Quiet hours end") }.show()
                        }, p.quietStart / 60, p.quietStart % 60, true).apply { setTitle("Quiet hours start") }.show()
                    }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Test the voice", "Play") { app.voice.maybeSpeak("Hi, I am Jester. I will remind you of everything. Even the embarrassing stuff.", explicit = true) }
                    HorizontalDivider(color = j.hair)
                    SwitchRow("Sound effects", "Bells, boings and whooshes. Off in quiet hours and on silent", p.sfx) { p.sfx = it; bump() }
                    HorizontalDivider(color = j.hair)
                    SwitchRow("Babble", "Little mumbling while Jester shows a message", p.babble) { p.babble = it; bump() }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Effects volume", "${(p.sfxVolume * 100).toInt()}%") {
                        val steps = listOf(0.15f, 0.35f, 0.55f, 0.75f, 1f)
                        p.sfxVolume = steps.firstOrNull { it > p.sfxVolume + 0.01f } ?: steps.first(); bump()
                    }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Max frame rate", "${p.maxFps} fps") {
                        p.maxFps = if (p.maxFps >= 120) 60 else 120; bump()
                        RobotService.start(ctx, RobotService.ACTION_APPLY_FX)
                    }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Jester's tone", p.tone) { p.tone = Looks.TONES[(Looks.TONES.indexOf(p.tone) + 1) % Looks.TONES.size]; bump() }
                }
            }
        }

        // smart triggers
        Column(Modifier.rise(280), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Label("SMART TRIGGERS")
                Text("Read on this phone. Only a short title is ever sent to Jester's server.", color = j.muted2, fontSize = 12.sp)
            }
            Card {
                Column {
                    TRIGGERS.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = j.hair)
                        SwitchRow(t.label, t.hint + (t.ships?.let { " · coming in $it" } ?: ""), p.trigger(t.key)) { p.setTrigger(t.key, it); bump() }
                    }
                }
            }
        }

        // bots
        BotsSection(app)

        // robot + pairing
        Column(Modifier.rise(400), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("FLOATING JESTER & PAIRING", Modifier.padding(horizontal = 4.dp))
            Card {
                Column {
                    SwitchRow("Auto-hide in full-screen apps", "Videos and games stay clean", p.autoHideFullscreen) { p.autoHideFullscreen = it; bump() }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Show Jester", "") { RobotService.start(ctx, RobotService.ACTION_SHOW) }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Hide for 1 hour", "") { RobotService.start(ctx, RobotService.ACTION_HIDE_HOUR) }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Pairing", if (p.configured) "Paired" else "No hub") { onNav(MainActivity.SCREEN_SETTINGS) }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Setup checklist", "") { onNav(MainActivity.SCREEN_SETUP) }
                    HorizontalDivider(color = j.hair)
                    ValueRow("Outbox", "${app.queue.pending().size} waiting · ${app.queue.failed().size} rejected") {
                        scope.launch { app.queue.retryFailed(System.currentTimeMillis()); app.kick(); bump() }
                    }
                }
            }
            Text("Jester can add, tick and snooze your own items and chat. It has no way to start work on the Mac.",
                color = j.muted2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
private fun BotsSection(app: JesterApp) {
    val j = LocalJ.current
    val scope = rememberCoroutineScope()
    var bots by remember { mutableStateOf<List<String>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(app.prefs.bot.ifEmpty { BotRepo.JESTER }) }
    LaunchedEffect(Unit) { bots = withContext(Dispatchers.IO) { app.hub.bots()?.first } ?: emptyList() }
    Column(Modifier.rise(340), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("YOUR BOTS (FLOATING ROBOT)", Modifier.padding(horizontal = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(BotRepo.JESTER, BotRepo.BUNDLED) + bots.filter { it != BotRepo.BUNDLED && it != BotRepo.JESTER }).take(6).forEachIndexed { i, b ->
                val sel = b == current
                val hop = if (sel) loop(1400) else 0f
                Column(Modifier.weight(1f).popIn(i * 60).border(2.dp, if (sel) j.accent else Color.Transparent, RoundedCornerShape(16.dp))
                    .background(j.surface, RoundedCornerShape(16.dp)).clickable {
                        scope.launch {
                            msg = "Loading $b…"
                            val ok = if (b == BotRepo.BUNDLED || b == BotRepo.JESTER) { app.prefs.bot = b; app.botVersion.value++; true } else withContext(Dispatchers.IO) { BotRepo.download(app, b) }
                            if (ok) current = b
                            msg = if (ok) "Floating robot: $b" else "Could not load $b"
                        }
                    }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    JesterFace(40.dp, modifier = Modifier.offset(y = (-6 * kotlin.math.sin(hop * Math.PI)).toFloat().dp))
                    Text(b, color = j.text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = j.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, color = LocalJ.current.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = modifier)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(LocalJ.current.surface, RoundedCornerShape(20.dp))) { content() }
}

@Composable
fun SwitchRow(label: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val j = LocalJ.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = j.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint.isNotEmpty()) Text(hint, color = j.muted2, fontSize = 12.sp)
        }
        JSwitch(on, onChange, label)
    }
}

@Composable
fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    val j = LocalJ.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = j.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = j.accentInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Device(name: String, state: String, live: Boolean, modifier: Modifier) {
    val j = LocalJ.current
    Column(modifier.background(j.surface, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(name, color = j.muted, fontSize = 12.sp)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) LiveDot(j.ok) else Box(Modifier.size(8.dp).border(2.dp, j.muted2, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(state, color = j.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
