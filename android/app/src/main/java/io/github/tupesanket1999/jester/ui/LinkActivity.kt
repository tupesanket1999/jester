package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.DeepLink
import io.github.tupesanket1999.jester.logic.Link
import io.github.tupesanket1999.jester.service.RobotService
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Handles jester:// links (QR pairing, routines, shortcuts). Links are untrusted, so every one is validated
 * (logic/DeepLink.kt) and needs one tap here before anything happens. Tokens are never shown or logged.
 */
class LinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = DeepLink.parse(intent?.dataString)
        val app = JesterApp.get(this)
        setContent { JesterTheme { Sheet(app, link) } }
    }

    @Composable
    private fun Sheet(app: JesterApp, link: Link?) {
        val j = LocalJ.current
        val scope = rememberCoroutineScope()
        var result by remember { mutableStateOf<String?>(null) }
        val (title, body, cta) = when (link) {
            is Link.Pair -> Triple("Pair this phone?", "Hub: ${runCatching { URI(link.hub).host }.getOrNull()}\nThe device token and push topic are stored encrypted on this phone.", "Pair")
            is Link.Add -> Triple("Add to Jester?", link.text, "Add")
            is Link.Ask -> Triple("Ask Jester?", link.text, "Ask")
            is Link.Focus -> Triple("Focus for ${link.minutes} min?", "Jester pings you when the time is up.", "Start")
            null -> Triple("That link isn't valid", "Jester only accepts well-formed jester:// links.", null)
        }
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().background(j.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}.navigationBarsPadding().padding(20.dp).rise(0),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JesterFace(44.dp)
                    Text(result ?: title, color = j.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                }
                if (result == null) Text(body, color = j.muted, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ finish() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(if (result == null) "Cancel" else "Close", color = j.text) }
                    if (cta != null && result == null) Button({
                        when (link) {
                            is Link.Pair -> {
                                val p = app.prefs
                                p.hubUrl = link.hub; p.ntfyServer = link.ntfy; p.token = link.token; p.ntfyTopic = link.topic; p.ntfyLastId = ""
                                RobotService.start(this@LinkActivity, RobotService.ACTION_SETTINGS_CHANGED)
                                result = "Checking the hub…"
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { app.refreshStatus() && app.refreshTasks() }
                                    if (ok) app.kick()
                                    result = if (ok) "Paired. Hello!" else "Saved, but the hub didn't answer yet"
                                }
                            }
                            is Link.Add -> { app.capture(link.text); result = "Added" }
                            is Link.Ask -> { app.sendText(link.text); MainActivity.open(this@LinkActivity, MainActivity.SCREEN_CHAT); finish() }
                            is Link.Focus -> { app.focus(link.minutes); result = "Focus on. Go!" }
                            null -> {}
                        }
                    }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = j.accent, contentColor = j.onAccent)) { Text(cta, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
