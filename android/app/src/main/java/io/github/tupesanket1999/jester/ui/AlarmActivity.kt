package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.NotifText
import io.github.tupesanket1999.jester.logic.ReplyParser
import io.github.tupesanket1999.jester.logic.TextClean
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Full-screen urgent alarm (opened by the reminder notification's full-screen intent, also over the lock screen).
 * Done / Snooze 15m / Tonight 9 PM. Jester shakes, rings ripple out, Done throws confetti.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getStringExtra("id")
        val title = TextClean.noEmoji(intent.getStringExtra("title") ?: "Reminder").ifEmpty { "Reminder" }
        val due = intent.getStringExtra("due")
        val level = intent.getIntExtra("level", 1)
        val app = JesterApp.get(this)
        setContent { JesterTheme { Alarm(app, id, title, due, level) } }
    }

    override fun onDestroy() { JesterApp.get(this).voice.stop(); super.onDestroy() }

    @Composable
    private fun Alarm(app: JesterApp, id: String?, title: String, due: String?, level: Int) {
        val j = LocalJ.current
        var outcome by remember { mutableStateOf<String?>(null) }
        var burst by remember { mutableIntStateOf(0) }
        val clock = remember { LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) }
        val late = remember { NotifText.body(due, level, java.time.Instant.now(), java.time.ZoneId.systemDefault()) }
        LaunchedEffect(Unit) { app.voice.let { it.maybeSpeak(it.reminderLine(title, urgent = true), urgent = true) } }
        LaunchedEffect(outcome) { if (outcome != null) { delay(2200); finish() } }

        Box(Modifier.fillMaxSize().background(j.alarmBg).safeDrawingPadding()) {
            if (outcome == null) Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("URGENT · ${NotifText.ordinal(level.coerceAtLeast(1)).uppercase()} PING", color = j.alarmInk, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.rise(0))
                val colon = if (LocalMotion.current) (loop(1000) < 0.5f) else true
                Row(Modifier.rise(80)) {
                    Text(clock.substring(0, 2), color = j.alarmText, fontSize = 76.sp, fontWeight = FontWeight.Bold)
                    Text(":", color = j.alarmText, fontSize = 76.sp, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(if (colon) 1f else 0.15f))
                    Text(clock.substring(3), color = j.alarmText, fontSize = 76.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.padding(top = 10.dp).size(210.dp).popIn(150), contentAlignment = Alignment.Center) {
                    for (k in 0..2) {
                        val r = loop(2000, k * 666)
                        Box(Modifier.size(210.dp).graphicsLayer { val s = 0.55f + 0.95f * r; scaleX = s; scaleY = s; alpha = (0.9f * (1 - r)) }
                            .border(3.dp, j.alarmBtn, CircleShape))
                    }
                    JesterFace(132.dp, shake = true)
                }
                Text(title, color = j.alarmText, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp).rise(250))
                Text("$late. I will keep ringing. I have no other hobbies.", color = j.alarmInk, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp).padding(top = 8.dp).rise(320))
                Spacer(Modifier.weight(1f))
                val breathe = 1f + 0.03f * pulse(800)
                Button(
                    onClick = { id?.let { app.done(it) }; app.voice.stop(); burst++; outcome = "done" },
                    modifier = Modifier.fillMaxWidth().height(64.dp).graphicsLayer { scaleX = breathe; scaleY = breathe },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = j.alarmBtn, contentColor = j.alarmBtnText),
                ) { Text("Done", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { id?.let { app.snooze(it, 15) }; app.voice.stop(); outcome = "snooze" },
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, j.alarmLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = j.alarmInk),
                    ) { Text("Snooze 15m", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { id?.let { app.snooze(it, ReplyParser.until(LocalDateTime.now(), 21, 0, sameDayOnly = true)) }; app.voice.stop(); outcome = "tonight" },
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, j.alarmLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = j.alarmInk),
                    ) { Text("Tonight 9 PM", fontWeight = FontWeight.SemiBold) }
                }
            } else Settled(outcome!!)
            Confetti(burst, count = 34)
        }
    }

    @Composable
    private fun Settled(outcome: String) {
        val j = LocalJ.current
        val (tag, head, line) = when (outcome) {
            "done" -> Triple("DONE", "Saga over.", "Future you sends thanks. Present me is going quiet.")
            "snooze" -> Triple("SNOOZED", "Back in 15.", "Fifteen minutes. I am counting. Loudly.")
            else -> Triple("MOVED", "Tonight, 9 PM.", "Bold. I will remind you to be bold again at 9.")
        }
        val a = remember { Animatable(0.4f) }
        LaunchedEffect(Unit) { a.animateTo(1f, tween(450)) }
        Column(Modifier.fillMaxSize().padding(24.dp).graphicsLayer { scaleX = a.value; scaleY = a.value; alpha = a.value },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            JesterFace(120.dp)
            Text(tag, color = j.alarmInk, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 14.dp))
            Text(head, color = j.alarmText, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(line, color = j.alarmInk, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp))
        }
    }
}
