package io.github.tupesanket1999.jester.ui

import io.github.tupesanket1999.jester.JesterApp
import io.github.tupesanket1999.jester.ocr.Ocr
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * "Share → Jester". Text goes straight to the inbox (queued, so it works offline).
 * Images are read on the device with ML Kit's bundled text recognizer (no upload), and the text goes to the inbox.
 * Shared content is untrusted: it is only ever captured as an inbox item, never acted on.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = JesterApp.get(this)
        if (intent?.action != Intent.ACTION_SEND) { finish(); return }
        val type = intent.type ?: ""
        when {
            type.startsWith("text/") -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                    .joinToString(" ").trim()
                if (text.isNotEmpty()) { app.capture(Ocr.clip(text)); toast("Sent to Jester's inbox") }
                finish()
            }
            type.startsWith("image/") -> {
                val uri = streamUri() ?: run { finish(); return }
                Ocr.read(this, uri) { text ->
                    when {
                        text == null -> toast("Could not read the image")
                        text.isEmpty() -> toast("No text found in the image")
                        else -> { app.capture(Ocr.clip("📷 $text")); toast("Screenshot text sent to Jester") }
                    }
                    finish()
                }
            }
            else -> finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
}
