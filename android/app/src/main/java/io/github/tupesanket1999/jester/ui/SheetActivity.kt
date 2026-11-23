package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.service.RobotService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

/**
 * Bottom sheet over whatever app is open (tap the floating Jester). MODE_CHAT: a standard text field, focused with
 * the keyboard up, so any dictation keyboard works; mic = speech recognizer. MODE_MENU: open the app, hide Jester.
 */
class SheetActivity : ComponentActivity() {
    private val mode = mutableStateOf(MODE_CHAT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        mode.value = intent.getStringExtra(EXTRA_MODE) ?: MODE_CHAT
        val app = JesterApp.get(this)
        setContent { JesterTheme { Sheet(app, mode.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mode.value = intent.getStringExtra(EXTRA_MODE) ?: MODE_CHAT
    }

    override fun onResume() { super.onResume(); JesterApp.get(this).sheetVisible = true; RobotService.start(this, RobotService.ACTION_SYNC) }
    override fun onPause() { super.onPause(); JesterApp.get(this).sheetVisible = false }

    @Composable
    private fun Sheet(app: JesterApp, mode: String) {
        val j = LocalJ.current
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }.imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().rise(0, 40.dp)
                    .background(j.bg, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {} // swallow taps inside the sheet
                    .navigationBarsPadding().padding(16.dp),
            ) {
                if (mode == MODE_MENU) Menu() else Chat(app)
            }
        }
    }

    @Composable
    private fun Chat(app: JesterApp) {
        val j = LocalJ.current
        val ringing by app.ringing.collectAsState()
        val lines by app.chat.collectAsState()
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        fun send() { val t = text.trim(); if (t.isNotEmpty()) { app.sendText(t); text = "" } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            JesterFace(32.dp)
            Text("Jester", color = j.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            TextButton(onClick = { MainActivity.open(this@SheetActivity, MainActivity.SCREEN_TODAY); finish() }) { Text("Open app", color = j.accentInk) }
            TextButton(onClick = { open(this@SheetActivity, MODE_MENU) }) { Text("More", color = j.accentInk) }
        }
        ringing.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).background(j.hotSurface, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, Modifier.weight(1f), color = j.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { app.snooze(r.id) }) { Text("Snooze 15m", color = j.hotInk) }
                Button(onClick = { app.done(r.id) }, colors = ButtonDefaults.buttonColors(containerColor = j.hot, contentColor = j.onHot)) { Text("Done") }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), reverseLayout = true) {
            items(lines.reversed()) { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (l.fromMe) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom) {
                    Text(
                        l.text + if (l.pending) "  ·" else "",
                        Modifier.widthIn(max = 280.dp).background(if (l.fromMe) j.accent else j.surface, RoundedCornerShape(14.dp)).padding(12.dp, 8.dp),
                        color = if (l.fromMe) j.onAccent else j.text,
                    )
                    if (!l.fromMe) IconButton({ app.voice.maybeSpeak(l.text, explicit = true) }, Modifier.size(32.dp)) {
                        Icon(JIcons.Speaker, "Read aloud", tint = j.muted2, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(2000) },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Ask or add (\"buy milk 6pm\")", color = j.muted2) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4, shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = j.accent, unfocusedBorderColor = j.line,
                    focusedContainerColor = j.surface, unfocusedContainerColor = j.surface, focusedTextColor = j.text, unfocusedTextColor = j.text, cursorColor = j.accent),
            )
            Spacer(Modifier.width(6.dp))
            IconButton({ VoiceActivity.open(this@SheetActivity) }, Modifier.size(48.dp).background(j.surface, RoundedCornerShape(16.dp))) {
                Icon(JIcons.Mic, "Speak", tint = j.accentInk)
            }
            Spacer(Modifier.width(6.dp))
            IconButton({ send() }, Modifier.size(48.dp).background(j.accent, RoundedCornerShape(16.dp))) { Icon(JIcons.Send, "Send", tint = j.onAccent) }
        }
        if (!app.prefs.configured) Text("No hub connected (optional: Me → Pairing). Typed text is kept and sent when one is.",
            color = j.muted2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        LaunchedEffect(Unit) { delay(150); focus.requestFocus(); keyboard?.show() }
    }

    @Composable
    private fun Menu() {
        val j = LocalJ.current
        val ctx = this
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            JesterFace(32.dp)
            Text("Jester", color = j.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        listOf(
            "Today" to { MainActivity.open(ctx, MainActivity.SCREEN_TODAY); finish() },
            "Chat" to { MainActivity.open(ctx, MainActivity.SCREEN_CHAT); finish() },
            "Me & settings" to { MainActivity.open(ctx, MainActivity.SCREEN_ME); finish() },
            "Hide for 1 hour" to { RobotService.start(ctx, RobotService.ACTION_HIDE_HOUR); finish() },
            "Hide Jester" to { RobotService.start(ctx, RobotService.ACTION_HIDE); finish() },
        ).forEachIndexed { i, (label, action) ->
            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 2.dp).rise(i * 50),
                shape = RoundedCornerShape(14.dp)) { Text(label, color = j.text) }
        }
        Text("Hidden Jester: reminders and push keep working. Show it again from the notification or the app. Long-press Jester to talk.",
            color = j.muted2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CHAT = "chat"
        const val MODE_MENU = "menu"

        fun open(ctx: Context, mode: String) {
            ctx.startActivity(Intent(ctx, SheetActivity::class.java).putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
