package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.notify.Notifier
import io.github.tupesanket1999.jester.ocr.Ocr
import io.github.tupesanket1999.jester.reminders.Alarms
import io.github.tupesanket1999.jester.service.RobotService
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Jester's app: Today / Chat / Me tabs, plus the setup checklist and pairing screens reachable from Me. */
class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf(SCREEN_TODAY)
    private val resumeTick = mutableIntStateOf(0) // re-check permissions when coming back from system settings
    private val quick = mutableStateOf("")
    private val chatInput = mutableStateOf("")
    private var speechTarget: ((String) -> Unit)? = null
    private var shotUri: Uri? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) speechTarget?.invoke(text)
        speechTarget = null
    }

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shotUri
        if (!ok || uri == null) return@registerForActivityResult
        Ocr.read(this, uri) { text ->
            when {
                text == null -> toast("Could not read the photo")
                text.isEmpty() -> toast("No text in that photo")
                else -> { quick.value = Ocr.clip(text, 300); screen.value = SCREEN_TODAY; toast("Read it. Check the text, then add.") }
            }
            runCatching { File(cacheDir, "shots/shot.jpg").delete() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = JesterApp.get(this)
        screen.value = intent.getStringExtra(EXTRA_SCREEN) ?: if (app.prefs.setupDone) SCREEN_TODAY else SCREEN_SETUP
        intent.getStringExtra(EXTRA_TEXT)?.let { chatInput.value = it }
        setContent { JesterTheme { Root(app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SCREEN)?.let { screen.value = it }
        intent.getStringExtra(EXTRA_TEXT)?.let { chatInput.value = it }
    }

    override fun onResume() {
        super.onResume(); resumeTick.intValue++
        val app = JesterApp.get(this)
        app.lookVersion.value++ // auto outfit may have changed with the hour
        if (app.prefs.setupDone) RobotService.start(this, RobotService.ACTION_SYNC)
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

    private fun listen(prompt: String, onText: (String) -> Unit) {
        speechTarget = onText
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try { speech.launch(i) } catch (e: ActivityNotFoundException) { speechTarget = null; toast("No speech recognizer on this phone") }
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "shot.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        shotUri = uri
        try { camera.launch(uri) } catch (e: ActivityNotFoundException) { toast("No camera app") }
    }

    @Composable
    private fun Root(app: JesterApp) {
        val j = LocalJ.current
        val s = screen.value
        val tab = if (s in TABS) s else null
        BackHandler(enabled = tab == null) { screen.value = SCREEN_ME }
        Column(Modifier.fillMaxSize().background(j.bg).safeDrawingPadding().imePadding()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(s, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { cur ->
                    when (cur) {
                        SCREEN_CHAT -> ChatScreen(app, chatInput) { listen("Tell Jester…") { t -> app.speakNextReply = true; app.sendText(t) } }
                        SCREEN_ME -> MeScreen(app) { screen.value = it }
                        SCREEN_SETUP -> Sub("Setup checklist") { SetupScreen(app, resumeTick.intValue) }
                        SCREEN_SETTINGS -> Sub("Pairing") { SettingsScreen(app) }
                        else -> TodayScreen(app, quick, onMic = { listen("Add a reminder…") { quick.value = it } }, onCamera = { takePhoto() }) { screen.value = it }
                    }
                }
            }
            if (tab != null) NavBar(tab) { screen.value = it }
        }
    }

    @Composable
    private fun NavBar(cur: String, onPick: (String) -> Unit) {
        val j = LocalJ.current
        Row(Modifier.fillMaxWidth().height(64.dp).background(j.nav)) {
            listOf(Triple(SCREEN_TODAY, "Today", JIcons.Today), Triple(SCREEN_CHAT, "Chat", JIcons.Chat), Triple(SCREEN_ME, "Me", JIcons.Me)).forEach { (id, label, icon) ->
                NavItem(label, icon, id == cur, Modifier.weight(1f)) { onPick(id) }
            }
        }
    }

    @Composable
    private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val j = LocalJ.current
        Column(modifier.fillMaxSize().clickable(onClickLabel = label, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val pill = if (selected) Modifier.popIn().background(j.accentSoft, RoundedCornerShape(50)) else Modifier
            Box(pill.size(56.dp, 28.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (selected) j.accentInk else j.muted2, modifier = Modifier.size(22.dp)) }
            Text(label, color = if (selected) j.accentInk else j.muted2, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }

    @Composable
    private fun Sub(title: String, content: @Composable () -> Unit) {
        val j = LocalJ.current
        Column(Modifier.fillMaxSize().background(j.bg).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { screen.value = SCREEN_ME }) { Text("‹ Me", color = j.accentInk) }
                Text(title, color = j.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }

    // ---------------- setup checklist (battery, overlay, notifications, exact alarms, full-screen, watch) ----------------

    /** [tick] changes on every onResume, so the marks are re-checked after returning from system settings. */
    @Suppress("UNUSED_PARAMETER")
    @Composable
    private fun SetupScreen(app: JesterApp, tick: Int) {
        val j = LocalJ.current
        val pm = getSystemService(PowerManager::class.java)
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val exact = Alarms.canExact(this)
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        val full = Notifier.canFullScreen(this)
        var watch by remember { mutableStateOf(app.prefs.watchConfirmed) }
        val hub = app.prefs.configured && app.prefs.ntfyTopic.isNotEmpty()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Each step keeps reminders on time. Tap a step to open the right system screen.", color = j.muted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Step("Allow notifications", notif, 0) { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
            Step("Display over other apps (floating Jester)", overlay, 1) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            Step("Alarms & reminders (offline backup)", exact, 2) {
                if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
            if (Build.VERSION.SDK_INT >= 34) Step("Full-screen alarm for urgent reminders", full, 3) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
            Step("Battery: don't optimise Jester", battery, 4) {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            Text("Samsung / Xiaomi / OnePlus also: Settings → Battery → background limits → set Jester to \"Never sleeping\" / \"No restrictions\".",
                color = j.muted2, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            Step("Optional: connect a hub (tasks, reminders, chat)", hub, 5) { screen.value = SCREEN_SETTINGS }
            Step("Optional: smartwatch notification mirroring", watch, 6) {
                app.prefs.watchConfirmed = true; watch = true
            }
            Text("If your watch mirrors phone notifications, allow Jester in your watch app. If the separate ntfy app is " +
                "also subscribed to Jester's topic, mute that topic in ntfy, or the watch buzzes twice with a raw tag line.",
                color = j.muted2, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
            Button(onClick = {
                app.prefs.setupDone = true
                RobotService.start(this@MainActivity, RobotService.ACTION_SHOW)
                screen.value = SCREEN_TODAY
            }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = j.accent, contentColor = j.onAccent)) { Text("Start Jester", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun Step(label: String, ok: Boolean, index: Int, onClick: () -> Unit) {
        val j = LocalJ.current
        Row(Modifier.fillMaxWidth().rise(index * 60).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(if (ok) j.accent else j.surface, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                if (ok) Icon(JIcons.Check, null, tint = j.onAccent, modifier = Modifier.size(16.dp).popIn())
            }
            Text(label, color = j.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (!ok) Text("Open", color = j.accentInk, fontWeight = FontWeight.SemiBold)
        }
    }

    // ---------------- pairing (manual; QR pairing goes through LinkActivity) ----------------

    @Composable
    private fun SettingsScreen(app: JesterApp) {
        val j = LocalJ.current
        val p = app.prefs
        val scope = rememberCoroutineScope()
        var hubUrl by remember { mutableStateOf(p.hubUrl) }
        var token by remember { mutableStateOf("") } // never shown back; blank = keep the stored one
        var server by remember { mutableStateOf(p.ntfyServer) }
        var topic by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A hub is optional: Jester works on its own without one. To sync tasks and reminders, open a jester://pair link from your hub or type the details here.",
                color = j.muted, fontSize = 14.sp)
            OutlinedTextField(hubUrl, { hubUrl = it }, Modifier.fillMaxWidth(), label = { Text("Hub URL (https)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (p.token.isEmpty()) "Device token" else "Device token (saved; type to replace)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("ntfy server") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (p.ntfyTopic.isEmpty()) "ntfy topic (optional push)" else "ntfy topic (saved; type to replace)") },
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = {
                if (hubUrl.isNotBlank() && !hubUrl.trim().startsWith("https://")) { msg = "Hub URL must start with https://"; return@Button }
                if (hubUrl.isBlank()) { p.hubUrl = ""; msg = "Saved. No hub: Jester runs on its own."; return@Button }
                p.hubUrl = hubUrl; p.ntfyServer = server
                if (token.isNotBlank()) p.token = token
                if (topic.isNotBlank()) { p.ntfyTopic = topic; p.ntfyLastId = "" }
                token = ""; topic = ""
                RobotService.start(this@MainActivity, RobotService.ACTION_SETTINGS_CHANGED)
                scope.launch {
                    msg = "Saved. Checking the hub…"
                    val ok = withContext(Dispatchers.IO) { app.refreshStatus() && app.refreshTasks() }
                    if (ok) app.kick()
                    msg = if (ok) "Saved. Hub OK (AI ${if (p.aiConfigured) "on" else "off"})." else "Saved. Hub not reachable or token rejected; writes stay queued."
                }
            }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = j.accent, contentColor = j.onAccent)) { Text("Save") }
            if (msg.isNotEmpty()) Text(msg, color = j.text)
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_TEXT = "text"
        const val SCREEN_TODAY = "today"
        const val SCREEN_CHAT = "chat"
        const val SCREEN_ME = "me"
        const val SCREEN_SETUP = "setup"
        const val SCREEN_SETTINGS = "settings"
        /** v0.2 name, still used by old notifications/menus. */
        const val SCREEN_CHECKLISTS = SCREEN_TODAY
        private val TABS = setOf(SCREEN_TODAY, SCREEN_CHAT, SCREEN_ME)

        fun open(ctx: Context, screen: String, text: String? = null) =
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SCREEN, screen).apply { text?.let { putExtra(EXTRA_TEXT, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
