package ru.chikizis.ritm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File

/** The user's own alarm melodies: phone ringtones and audio files copied into the app. */
object Sounds {
    private const val MAX_BYTES = 25L * 1024 * 1024

    fun dir(ctx: Context): File = File(ctx.filesDir, "sounds").apply { mkdirs() }

    /** Copies a picked audio file into the app so it keeps working even if the original is moved. Returns (id, name). */
    fun importFile(ctx: Context, uri: Uri): Pair<String, String>? {
        val cr = ctx.contentResolver
        var name = "Своя мелодия"
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        if (size > MAX_BYTES) return null
        val ext = name.substringAfterLast('.', "mp3").lowercase().take(5).filter { it.isLetterOrDigit() }.ifEmpty { "mp3" }
        val file = File(dir(ctx), "s" + System.currentTimeMillis().toString(36) + "." + ext)
        return try {
            cr.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
            if (file.length() > MAX_BYTES || file.length() == 0L) { file.delete(); return null }
            if (!playable(ctx, Uri.fromFile(file))) { file.delete(); return null }
            "file:" + file.name to name.substringBeforeLast('.')
        } catch (e: Exception) {
            file.delete(); null
        }
    }

    fun ringtone(ctx: Context, uri: Uri): Pair<String, String> {
        val title = try { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) } catch (_: Exception) { null }
        return "uri:$uri" to (title ?: "Мелодия телефона")
    }

    fun playable(ctx: Context, uri: Uri): Boolean = try {
        MediaPlayer().run { setDataSource(ctx, uri); prepare(); release(); true }
    } catch (_: Exception) { false }

    // ---- preview while choosing
    private var preview: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun preview(ctx: Context, sound: String) {
        stopPreview()
        val uri = AlarmService.soundRes(ctx, sound) ?: return
        try {
            preview = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                setVolume(0.6f, 0.6f)
                start()
            }
            handler.postDelayed({ stopPreview() }, 10_000)
        } catch (_: Exception) { stopPreview() }
    }

    fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        try { preview?.stop() } catch (_: Exception) {}
        try { preview?.release() } catch (_: Exception) {}
        preview = null
    }
}
