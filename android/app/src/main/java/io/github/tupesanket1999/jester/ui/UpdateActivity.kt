package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.Updates
import io.github.tupesanket1999.jester.notify.Notifier
import io.github.tupesanket1999.jester.update.UpdateState
import io.github.tupesanket1999.jester.update.Updater
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * "Jester x.y.z is available": what's new, size, Update / Later / Skip this version. Update downloads the APK,
 * checks it and hands it to Android's installer (which asks for confirmation). Opened from the update notification
 * or from Me -> Check for updates; the release comes from Prefs.pendingUpdate.
 */
class UpdateActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    /** User was sent to "install unknown apps" for Jester; continue the update when they come back with it allowed. */
    private val awaitingPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = JesterApp.get(this)
        Updater.resetIfSettled()
        val release = Updates.fromJson(app.prefs.pendingUpdate)?.takeIf { Updates.isNewer(it, Updater.installedVersion(this)) }
        setContent { JesterTheme { Sheet(app, release) } }
    }

    override fun onResume() {
        super.onResume(); resumeTick.intValue++
    }

    private fun begin(app: JesterApp, r: Updates.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            awaitingPermission.value = true
            Toast.makeText(this, "Allow Jester to install updates, then come back.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else {
            awaitingPermission.value = false
            Updater.start(app, r)
        }
    }

    @Composable
    private fun Sheet(app: JesterApp, release: Updates.Release?) {
        val j = LocalJ.current
        val st by Updater.state.collectAsState()
        val busy = st is UpdateState.Downloading || st is UpdateState.Verifying || st is UpdateState.Installing

        // back from system settings: carry on if the permission is now granted
        LaunchedEffect(resumeTick.intValue) {
            if (awaitingPermission.value && release != null && packageManager.canRequestPackageInstalls()) begin(app, release)
        }

        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { if (!busy) finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().background(j.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}.navigationBarsPadding().padding(20.dp).rise(0),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JesterFace(44.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(if (release != null) "Jester ${release.version} is available" else "You're up to date",
                            color = j.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (release != null) "You have ${Updater.installedVersion(this@UpdateActivity)}" + sizeText(release.size)?.let { " · $it download" }.orEmpty()
                            else "Jester ${Updater.installedVersion(this@UpdateActivity)}",
                            color = j.muted, fontSize = 13.sp)
                    }
                }
                if (release == null) {
                    CloseRow("Close")
                    return@Column
                }
                if (release.notes.isNotEmpty() && st is UpdateState.Idle) {
                    Text("WHAT'S NEW", color = j.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                    Text(release.notes, color = j.text, fontSize = 14.sp, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                }
                when (val s = st) {
                    is UpdateState.Downloading -> Progress("Downloading" + if (s.total > 0) " ${(s.done * 100 / s.total).coerceIn(0, 100)}%" else "",
                        if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f)
                    UpdateState.Verifying -> Progress("Checking the download…", 1f)
                    UpdateState.Installing -> Text("Waiting for Android's install prompt. Confirm it to finish.", color = j.muted, fontSize = 14.sp)
                    UpdateState.Installed -> Text("Installed. Jester is restarting.", color = j.ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    is UpdateState.Failed -> Text(s.message, color = j.hotInk, fontSize = 14.sp)
                    UpdateState.Idle -> if (awaitingPermission.value) Text(
                        "Android needs your OK to let Jester install updates. Allow it in Settings, then come back.", color = j.muted, fontSize = 14.sp)
                }
                if (!busy) {
                    if (st is UpdateState.Installed) CloseRow("Close") else {
                        Button({ begin(app, release) }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = j.accent, contentColor = j.onAccent)) {
                            Text(if (st is UpdateState.Failed) "Try again" else "Update", fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({ finish() }, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) { Text("Later", color = j.text) }
                            TextButton({
                                app.prefs.skippedUpdate = release.tag
                                Notifier.cancelUpdate(app)
                                finish()
                            }, Modifier.weight(1f).height(44.dp)) { Text("Skip this version", color = j.muted) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Progress(label: String, frac: Float) {
        val j = LocalJ.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = j.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.fillMaxWidth().height(8.dp).background(j.line, RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.01f)).fillMaxHeight().background(j.accent, RoundedCornerShape(50)))
            }
        }
    }

    @Composable
    private fun CloseRow(label: String) {
        val j = LocalJ.current
        OutlinedButton({ finish() }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(label, color = j.text) }
    }

    private fun sizeText(bytes: Long): String? =
        if (bytes <= 0) null else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(ctx: Context) = ctx.startActivity(intent(ctx))
    }
}
