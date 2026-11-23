package io.github.tupesanket1999.jester.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** On-device text recognition (ML Kit, bundled Latin model). The image never leaves the phone. */
object Ocr {
    fun read(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val image = try { InputImage.fromFilePath(ctx, uri) } catch (e: Exception) { done(null); return }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { r -> done(r.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")) }
            .addOnFailureListener { done(null) }
    }

    /** The hub keeps inbox lines short. */
    fun clip(s: String, max: Int = 1000) = if (s.length <= max) s else s.take(max - 1) + "…"
}
