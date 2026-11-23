package io.github.tupesanket1999.jester.voice

import io.github.tupesanket1999.jester.Prefs
import io.github.tupesanket1999.jester.logic.VoicePolicy
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Calendar
import java.util.Locale

/**
 * Jester's voice: Android's built-in TextToSpeech (offline, free). Nothing is sent anywhere.
 * Slightly higher pitch and a touch faster so it sounds like a small robot, in the device language when the engine has it.
 */
class Voice(private val ctx: Context, private val prefs: Prefs) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val queued = ArrayList<String>()
    private val picker = LinePicker()

    /** Random in-character line from [lines] for [pool]; never the same line twice in a row for that pool. */
    fun pick(pool: String, lines: List<String>): String = picker.pick(pool, lines)

    /** Spoken form of a reminder, in the jester's voice. */
    fun reminderLine(title: String, urgent: Boolean): String =
        if (urgent) pick("urgent", listOf("Hear ye, hear ye! Most urgent, my liege: ", "Alarm, alarm! Bestir thyself, my liege: ")) + title
        else pick("personal", listOf("Hear ye! A royal reminder: ", "Pardon the bells, my liege. Reminder: ")) + title

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            // the device language if the engine has it, else US English
            val dev = Locale.getDefault()
            t.language = if (t.isLanguageAvailable(dev) >= TextToSpeech.LANG_AVAILABLE) dev else Locale.US
            t.setPitch(1.3f)
            t.setSpeechRate(1.08f)
            ready = true
            synchronized(queued) { queued.forEach { say(it) }; queued.clear() }
        }
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "jester-" + text.hashCode())
    }

    /** Speak if the policy allows ([explicit] = the speaker button, always speaks). Returns whether it spoke. */
    fun maybeSpeak(text: String, urgent: Boolean = false, explicit: Boolean = false): Boolean {
        val c = Calendar.getInstance()
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (!VoicePolicy.shouldSpeak(prefs.voiceMode, urgent, earbudsConnected(), minute, prefs.quietStart, prefs.quietEnd, explicit)) return false
        val clean = VoicePolicy.clean(text)
        if (clean.isEmpty()) return false
        ensure()
        if (ready) say(clean) else synchronized(queued) { queued += clean }
        return true
    }

    fun stop() { tts?.stop() }

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    fun earbudsConnected(): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) { types += AudioDeviceInfo.TYPE_BLE_HEADSET; types += AudioDeviceInfo.TYPE_BLE_SPEAKER }
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }
}
