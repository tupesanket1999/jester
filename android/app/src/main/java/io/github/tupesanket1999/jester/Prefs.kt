package io.github.tupesanket1999.jester

import io.github.tupesanket1999.jester.logic.Looks
import io.github.tupesanket1999.jester.logic.VoiceMode
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings. Secrets (device token, ntfy topic) live in EncryptedSharedPreferences backed by an Android Keystore key;
 * they are never logged and never backed up (allowBackup=false). Everything else is plain prefs.
 */
class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val plain: SharedPreferences = app.getSharedPreferences("jester", Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy { openSecure() }

    private fun openSecure(): SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            app, "jester-hub",
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try { create() } catch (e: Exception) {
            // Keystore key lost (e.g. restored device): the encrypted file is unreadable. Start clean; user re-enters token.
            app.deleteSharedPreferences("jester-hub")
            create()
        }
    }

    var hubUrl: String
        get() = plain.getString("hubUrl", "")!!.trimEnd('/')
        set(v) = plain.edit().putString("hubUrl", v.trim().trimEnd('/')).apply()

    var token: String
        get() = secure.getString("token", "") ?: ""
        set(v) = secure.edit().putString("token", v.trim()).apply()

    var ntfyServer: String
        get() = plain.getString("ntfyServer", "https://ntfy.sh")!!.trimEnd('/')
        set(v) = plain.edit().putString("ntfyServer", v.trim().trimEnd('/')).apply()

    var ntfyTopic: String
        get() = secure.getString("ntfyTopic", "") ?: ""
        set(v) = secure.edit().putString("ntfyTopic", v.trim()).apply()

    /** Last ntfy message id seen, so a reconnect asks for `since=<id>` and nothing is missed. */
    var ntfyLastId: String
        get() = plain.getString("ntfyLastId", "") ?: ""
        set(v) = plain.edit().putString("ntfyLastId", v).apply()

    /** Chosen bot pack ("" = the hub's current one, or the bundled pixel pack). */
    var bot: String
        get() = plain.getString("bot", "") ?: ""
        set(v) = plain.edit().putString("bot", v).apply()

    var hiddenUntil: Long
        get() = plain.getLong("hiddenUntil", 0)
        set(v) = plain.edit().putLong("hiddenUntil", v).apply()

    /** "Hide" from the menu: robot off until shown again from the app or the notification (push keeps running). */
    var robotHidden: Boolean
        get() = plain.getBoolean("robotHidden", false)
        set(v) = plain.edit().putBoolean("robotHidden", v).apply()

    var autoHideFullscreen: Boolean
        get() = plain.getBoolean("autoHideFullscreen", true)
        set(v) = plain.edit().putBoolean("autoHideFullscreen", v).apply()

    var aiConfigured: Boolean
        get() = plain.getBoolean("aiConfigured", false)
        set(v) = plain.edit().putBoolean("aiConfigured", v).apply()

    var robotX: Int
        get() = plain.getInt("robotX", -1)
        set(v) = plain.edit().putInt("robotX", v).apply()

    var robotY: Int
        get() = plain.getInt("robotY", 300)
        set(v) = plain.edit().putInt("robotY", v).apply()

    var setupDone: Boolean
        get() = plain.getBoolean("setupDone", false)
        set(v) = plain.edit().putBoolean("setupDone", v).apply()

    var watchConfirmed: Boolean
        get() = plain.getBoolean("watchConfirmed", false)
        set(v) = plain.edit().putBoolean("watchConfirmed", v).apply()

    val configured: Boolean get() = hubUrl.startsWith("https://") && token.isNotEmpty()

    // ---- v0.3 look, voice and triggers (plain prefs: nothing secret) ----

    var theme: String
        get() = plain.getString("theme", "circus") ?: "circus"
        set(v) = plain.edit().putString("theme", v).apply()

    var autoTheme: Boolean
        get() = plain.getBoolean("autoTheme", false)
        set(v) = plain.edit().putBoolean("autoTheme", v).apply()

    var tone: String
        get() = plain.getString("tone", "Cheeky") ?: "Cheeky"
        set(v) = plain.edit().putString("tone", v).apply()

    var voiceMode: VoiceMode
        get() = runCatching { VoiceMode.valueOf(plain.getString("voiceMode", "EARBUDS")!!) }.getOrDefault(VoiceMode.EARBUDS)
        set(v) = plain.edit().putString("voiceMode", v.name).apply()

    /** Local quiet hours for voice, minutes since midnight (default 23:00–07:00). */
    var quietStart: Int
        get() = plain.getInt("quietStart", 23 * 60)
        set(v) = plain.edit().putInt("quietStart", v).apply()

    var quietEnd: Int
        get() = plain.getInt("quietEnd", 7 * 60)
        set(v) = plain.edit().putInt("quietEnd", v).apply()

    // ---- jester sound + frame rate ----
    var sfx: Boolean
        get() = plain.getBoolean("sfx", true)
        set(v) = plain.edit().putBoolean("sfx", v).apply()

    var babble: Boolean
        get() = plain.getBoolean("babble", true)
        set(v) = plain.edit().putBoolean("babble", v).apply()

    var sfxVolume: Float
        get() = plain.getFloat("sfxVolume", 0.35f)
        set(v) = plain.edit().putFloat("sfxVolume", v).apply()

    /** Eco mode: lower frame rates to save battery. */
    var eco: Boolean
        get() = plain.getBoolean("eco", false)
        set(v) = plain.edit().putBoolean("eco", v).apply()

    /** 60 or 120 */
    var maxFps: Int
        get() = if (plain.getInt("maxFps", 60) >= 120) 120 else 60
        set(v) = plain.edit().putInt("maxFps", v).apply()

    fun trigger(key: String): Boolean = plain.getBoolean("trig_$key", key == "battery")
    fun setTrigger(key: String, on: Boolean) = plain.edit().putBoolean("trig_$key", on).apply()

    /** Last /api/status bits shown on Me (Claude Code state, AI quota). */
    var macState: String
        get() = plain.getString("macState", "") ?: ""
        set(v) = plain.edit().putString("macState", v).apply()

    var aiUsed: Int
        get() = plain.getInt("aiUsed", -1)
        set(v) = plain.edit().putInt("aiUsed", v).apply()

    var aiCap: Int
        get() = plain.getInt("aiCap", -1)
        set(v) = plain.edit().putInt("aiCap", v).apply()

    var aiProvider: String
        get() = plain.getString("aiProvider", "") ?: ""
        set(v) = plain.edit().putString("aiProvider", v).apply()

    /** Effective outfit right now (auto picks by hour). */
    fun currentTheme(hour: Int): String = if (autoTheme) Looks.auto(hour) else theme
}
