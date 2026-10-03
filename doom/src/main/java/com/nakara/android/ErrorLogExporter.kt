package com.nakara.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies the native ZScript error log (written by the engine into the game config
 * directory during script compilation) into the public Downloads folder so it can
 * be shared easily.
 *
 * Scoped-storage safe: on Android 10+ (API 29+) the copy goes through MediaStore,
 * which needs no storage permission. On older devices it falls back to a direct
 * file copy into the public Downloads directory (best effort).
 */
object ErrorLogExporter {
    const val LOG_NAME = "nakara-zscript-errors.log"

    /**
     * Call off the main thread. Returns true if a non-empty log was exported to
     * Downloads.
     */
    fun exportIfPresent(ctx: Context): Boolean {
        val src = File(AppSettings.getQuakeFullDir(), LOG_NAME)
        if (!src.isFile || src.length() == 0L) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(ctx, src)
            } else {
                exportViaDirectCopy(src)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun exportViaMediaStore(ctx: Context, src: File): Boolean {
        val cr = ctx.contentResolver
        // Remove any previous export so Downloads always holds exactly one current log.
        cr.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(LOG_NAME)
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, LOG_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        cr.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
        return true
    }

    private fun exportViaDirectCopy(src: File): Boolean {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.isDirectory && !downloads.mkdirs()) return false
        src.copyTo(File(downloads, LOG_NAME), overwrite = true)
        return true
    }
}
