package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Long-press on the floating Jester: the system speech recognizer opens, the words go to Jester's chat,
 * and the reply is read aloud. No UI of its own.
 */
class VoiceActivity : ComponentActivity() {
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            val app = JesterApp.get(this)
            app.speakNextReply = true
            app.sendText(text)
            Toast.makeText(applicationContext, "Jester heard: $text", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Jester…")
        try { speech.launch(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(applicationContext, "No speech recognizer on this phone", Toast.LENGTH_SHORT).show(); finish()
        }
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
