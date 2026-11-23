package io.github.tupesanket1999.jester.update

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.logic.Updates
import io.github.tupesanket1999.jester.notify.Notifier
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

sealed class CheckResult {
    data class UpToDate(val version: String) : CheckResult()
    data class Available(val release: Updates.Release) : CheckResult()
    object Failed : CheckResult()
}

/** What the update sheet shows. Lives here (not in the activity) so a download survives rotation and leaving the sheet. */
sealed class UpdateState {
    object Idle : UpdateState()
    data class Downloading(val done: Long, val total: Long) : UpdateState()
    object Verifying : UpdateState()
    object Installing : UpdateState()
    object Installed : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * In-app updater: asks the GitHub releases API for a newer android-vX.Y.Z release, downloads its APK into
 * cacheDir/updates, verifies size + SHA-256 (when GitHub gives a digest), and hands it to PackageInstaller.
 * Android still asks the user to confirm, and only accepts an APK signed with the same key as the installed app.
 * Nothing here sends anything about the user: a plain GET with a generic User-Agent.
 */
object Updater {
    private const val API = "https://api.github.com/repos/tupesanket1999/jester/releases?per_page=30"

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val checking = AtomicBoolean(false)

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

    // ---------------------------------------------------------------- check

    /** Automatic check: only when switched on and a day has passed; silent on failure. Safe to call from anywhere, often. */
    fun autoCheck(app: JesterApp) {
        val p = app.prefs
        if (!p.autoUpdateCheck || !Updates.due(p.lastUpdateCheck, System.currentTimeMillis())) return
        if (!checking.compareAndSet(false, true)) return
        app.scope.launch {
            try { check(app, auto = true) } catch (e: Exception) { /* silent */ } finally { checking.set(false) }
        }
    }

    /** Blocking (call on IO). Manual checks ignore "skipped"; automatic ones post a notification once per release. */
    fun check(app: JesterApp, auto: Boolean): CheckResult {
        val installed = installedVersion(app)
        val body = fetch(installed)
        val now = System.currentTimeMillis()
        if (body == null) {
            if (auto) app.prefs.lastUpdateCheck = Updates.lastCheckAfterFailure(now)
            return CheckResult.Failed
        }
        app.prefs.lastUpdateCheck = now
        val r = Updates.pick(body)
        if (r == null || !Updates.isNewer(r, installed)) {
            app.prefs.pendingUpdate = ""
            return CheckResult.UpToDate(installed)
        }
        app.prefs.pendingUpdate = Updates.toJson(r)
        if (auto && Updates.shouldNotify(r, installed, app.prefs.skippedUpdate, app.prefs.notifiedUpdate)) {
            app.prefs.notifiedUpdate = r.tag
            Notifier.updateAvailable(app, r)
        }
        return CheckResult.Available(r)
    }

    private fun fetch(installed: String): String? {
        val c = try { URL(API).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
        return try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Jester-Android/$installed")
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: IOException) {
            null
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- download + install

    /** Download, verify, install. Progress and errors go to [state]. No-op while one is already running. */
    fun start(app: JesterApp, r: Updates.Release) {
        synchronized(this) {
            val s = state.value
            if (s is UpdateState.Downloading || s is UpdateState.Verifying || s is UpdateState.Installing) return
            state.value = UpdateState.Downloading(0, r.size)
        }
        Notifier.cancelUpdate(app)
        app.scope.launch {
            try {
                val f = download(app, r)
                state.value = UpdateState.Installing
                install(app, f)
                // the result arrives in InstallReceiver
            } catch (e: Exception) {
                state.value = UpdateState.Failed(if (e is UpdateException) e.message ?: "Update failed" else "Download failed. Check your connection and try again.")
            }
        }
    }

    /** Leave a finished/failed state behind when the sheet opens again. */
    fun resetIfSettled() {
        synchronized(this) {
            val s = state.value
            if (s is UpdateState.Failed || s is UpdateState.Installed) state.value = UpdateState.Idle
        }
    }

    private class UpdateException(msg: String) : IOException(msg)

    private fun download(ctx: Context, r: Updates.Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, Updates.apkFileName(r.version))
        val part = File(dir, dest.name + ".part")
        part.delete(); dest.delete()
        val c = URL(r.url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true // github.com -> objects.githubusercontent.com (https to https)
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Jester-Android/${installedVersion(ctx)}")
            val code = c.responseCode
            if (code != 200) throw UpdateException("Download failed (HTTP $code).")
            val total = if (r.size > 0) r.size else c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else done.toInt() / (256 * 1024)
                        if (pct != lastPct) { lastPct = pct; state.value = UpdateState.Downloading(done, total) }
                    }
                }
            }
            state.value = UpdateState.Verifying
            if (r.size > 0 && part.length() != r.size) {
                part.delete(); throw UpdateException("The download was incomplete. Nothing was installed.")
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256 != null && hex != r.sha256) {
                part.delete(); throw UpdateException("The download did not match its checksum, so it was deleted. Nothing was installed.")
            }
            if (!part.renameTo(dest)) { part.delete(); throw UpdateException("Could not save the download.") }
            return dest
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun install(ctx: Context, f: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(f.length()) }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                f.inputStream().use { input ->
                    s.openWrite("jester.apk", 0, f.length()).use { out -> input.copyTo(out); s.fsync(out) }
                }
                // the system fills in status extras, so this PendingIntent has to be mutable (explicit target, so allowed)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Delete cached APKs of the installed version or older (and half-finished downloads). Call on app start, off the main thread. */
    fun cleanup(ctx: Context) {
        val installed = installedVersion(ctx)
        File(ctx.cacheDir, "updates").listFiles()?.forEach { if (Updates.isStaleApk(it.name, installed)) it.delete() }
    }

    internal fun friendlyFailure(status: Int, message: String?): String {
        val m = message ?: ""
        return when {
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled."
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE || status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                m.contains("INCONSISTENT_CERTIFICATES") || m.contains("UPDATE_INCOMPATIBLE") ->
                "Android refused it: the update is signed with a different key than the installed Jester."
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
            else -> "Install failed" + if (m.isNotBlank()) ": ${m.take(120)}" else "."
        }
    }
}

/** PackageInstaller results: ask for the user's confirmation, then report success or failure to the update sheet. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) { Updater.state.value = UpdateState.Failed("Android did not show the install prompt."); return }
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { Updater.state.value = UpdateState.Failed("Could not open the install prompt.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = UpdateState.Installed
            else -> Updater.state.value = UpdateState.Failed(Updater.friendlyFailure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }
}
