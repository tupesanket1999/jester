package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.ChatLine
import io.github.tupesanket1999.jester.JesterApp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun ChatScreen(app: JesterApp, input: MutableState<String>, onMic: () -> Unit) {
    val j = LocalJ.current
    val lines by app.chat.collectAsState()
    val typing = lines.any { it.fromMe && it.pending }
    val list = rememberLazyListState()
    val seen = remember { mutableStateOf(lines.size) } // bubbles already on screen when we opened do not re-animate
    LaunchedEffect(lines.size, typing) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size + (if (typing) 1 else 0)) }
    fun send(text: String) { val t = text.trim(); if (t.isNotEmpty()) { app.sendText(t); input.value = "" } }

    Column(Modifier.fillMaxSize().background(j.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp).rise(0), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(j.accentSoft, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                JesterFace(36.dp)
                Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(12.dp).background(j.bg, CircleShape), contentAlignment = Alignment.Center) { LiveDot(j.ok, 8.dp) }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Jester", color = j.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val p = app.prefs
                val status = when {
                    typing -> "typing…"
                    !p.configured -> "no hub: messages wait in the outbox"
                    p.aiCap > 0 -> "online · ${p.aiProvider.ifEmpty { "AI" }} · ${p.aiUsed}/${p.aiCap} today"
                    else -> "online"
                }
                Text(status, color = j.muted, fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = j.hair)

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lines.isEmpty()) item {
                Text("Ask me anything, or tell me what to remember. Try \"remind me to call mom at 6\".", color = j.muted, fontSize = 14.sp, modifier = Modifier.rise(100))
            }
            itemsIndexed(lines, key = { i, l -> "${l.at}-$i" }) { i, l ->
                val fresh = i >= seen.value
                Bubble(app, l, if (fresh) 0 else -1)
            }
            if (typing) item { TypingDots() }
        }
        LaunchedEffect(Unit) { seen.value = lines.size }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("What's left today?", "Plan my evening", "Tell me a joke").forEachIndexed { i, s ->
                Text(s, color = j.text, fontSize = 13.sp, modifier = Modifier.rise(300 + i * 80).border(1.dp, j.line, RoundedCornerShape(50))
                    .clickable { send(s) }.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input.value, { input.value = it.take(2000) }, Modifier.weight(1f).heightIn(min = 52.dp),
                placeholder = { Text("Ask or tell Jester anything", color = j.muted2) }, shape = RoundedCornerShape(16.dp), maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input.value) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = j.accent, unfocusedBorderColor = j.line,
                    focusedContainerColor = j.surface, unfocusedContainerColor = j.surface, focusedTextColor = j.text, unfocusedTextColor = j.text, cursorColor = j.accent),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onMic, Modifier.size(48.dp).border(1.dp, j.line, RoundedCornerShape(16.dp)).background(j.surface, RoundedCornerShape(16.dp))) {
                Icon(JIcons.Mic, "Speak to Jester", tint = j.accentInk)
            }
            Spacer(Modifier.width(6.dp))
            IconButton({ send(input.value) }, Modifier.size(48.dp).background(j.accent, RoundedCornerShape(16.dp))) { Icon(JIcons.Send, "Send", tint = j.onAccent) }
        }
    }
}

@Composable
private fun Bubble(app: JesterApp, l: ChatLine, delay: Int) {
    val j = LocalJ.current
    val anim = if (delay >= 0) Modifier.popIn(delay) else Modifier
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (l.fromMe) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                l.text + if (l.pending) "  ·" else "", color = if (l.fromMe) j.onAccent else j.text, fontSize = 15.sp, lineHeight = 21.sp,
                modifier = anim.widthIn(max = 280.dp)
                    .background(if (l.fromMe) j.accent else j.surface,
                        if (l.fromMe) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            if (!l.fromMe) IconButton({ app.voice.maybeSpeak(l.text, explicit = true) }, Modifier.size(36.dp)) {
                Icon(JIcons.Speaker, "Read aloud", tint = j.muted2, modifier = Modifier.size(18.dp))
            }
        }
        var undone by remember { mutableStateOf(false) }
        l.acts.forEachIndexed { i, a ->
            val label = when (a.kind) {
                "added" -> "Added: ${a.title ?: ""}"
                "done" -> "Done: ${a.title ?: ""}"
                "remembered" -> "Remembered"
                "forgot" -> "Forgot: ${a.title ?: ""}"
                else -> a.kind
            }
            Row(Modifier.padding(top = 6.dp).popIn(200 + i * 80).background(j.surface, RoundedCornerShape(12.dp)).border(1.dp, j.line, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(JIcons.Check, null, tint = j.accent, modifier = Modifier.size(16.dp))
                Text(if (undone) "Undone" else label, color = j.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                if (a.kind == "added" && a.id != null && !undone) Text("Undo", color = j.accentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(j.accentSoft, RoundedCornerShape(8.dp)).clickable { app.done(a.id); undone = true }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val j = LocalJ.current
    val t = loop(1000)
    Row(Modifier.popIn().background(j.surface, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (k in 0..2) {
            val ph = ((t - k * 0.15f) % 1f + 1f) % 1f
            val up = if (ph < 0.3f) sin(ph / 0.3f * PI).toFloat() else 0f
            Box(Modifier.size(7.dp).graphicsLayer { translationY = -6f * density * up; alpha = 0.45f + 0.55f * up }.background(j.accent, CircleShape))
        }
    }
}
