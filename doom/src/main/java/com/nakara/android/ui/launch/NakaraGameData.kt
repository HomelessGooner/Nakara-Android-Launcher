package com.nakara.android.ui.launch

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nakara.android.R
import com.nakara.android.ui.formatFileSize

// ---------------------------------------------------------------------------
// Nakara game-data delivery — offline-first and bring-your-own-data.
//
// The 651 MB Nk.ipk3 can never ship inside the APK, and this app never
// downloads it: on startup it hash-checks the game-data directory, and if
// Nk.ipk3 is missing (or corrupt) the user picks the file from their own
// legally obtained copy of the game via the system file picker.
// ---------------------------------------------------------------------------

/** File name of the Nakara game data as it lives in the game-data directory. */
const val NAKARA_IPK3_NAME = "Nk.ipk3"

/** SHA-256 of the genuine Nk.ipk3 (651 MB). Anything that doesn't match is discarded. */
const val NAKARA_IPK3_SHA256 =
    "0e44dba26de812e04e7f127f55adfb435950b644a03a06765e22af08f46addbd"

/** Exact byte size of the genuine Nk.ipk3 — checked before hashing to skip bad files fast. */
const val NAKARA_IPK3_SIZE = 651408207L

/** UI state for the first-run game-data gate. */
sealed interface NakaraDataState {
    /** Hashing the existing file / deciding what the user needs to do. */
    data object Checking : NakaraDataState
    /** No valid Nk.ipk3 found — the user must provide the file (or use the fallback). */
    data object Missing : NakaraDataState
    /** Copying a user-picked file into the game-data directory. */
    data class Copying(val bytesRead: Long, val totalBytes: Long) : NakaraDataState
    /** A valid Nk.ipk3 is in the game-data directory. */
    data object Ready : NakaraDataState
    /** Copy, download, or verification failed; [message] is user-facing. */
    data class Failed(val message: String) : NakaraDataState
}

fun nakaraIpk3File(baseDir: String): File = File(baseDir, NAKARA_IPK3_NAME)

/** Hash-only check: true when [baseDir]/Nk.ipk3 exists and matches [NAKARA_IPK3_SHA256]. */
suspend fun hasValidNakaraIpk3(baseDir: String): Boolean = withContext(Dispatchers.IO) {
    val target = nakaraIpk3File(baseDir)
    target.exists() && target.length() == NAKARA_IPK3_SIZE && sha256Hex(target) == NAKARA_IPK3_SHA256
}

/**
 * Zero-picker import: looks for Nk.ipk3 that was placed (via `adb push`, a file
 * manager, or the BlueStacks installer script) into the app's own external
 * files dir — always readable, no permissions or system picker needed. When the
 * file there is genuine (size + SHA-256), it is moved into [baseDir].
 *
 * This is the BlueStacks-friendly path: the system file picker on emulators
 * often cannot browse to /sdcard, so the installer copies the file here
 * directly and the app picks it up on the next launch.
 *
 * @return true when a valid Nk.ipk3 ended up in [baseDir].
 */
suspend fun adoptPushedNakaraIpk3(ctx: Context, baseDir: String): Boolean =
    withContext(Dispatchers.IO) {
        val target = nakaraIpk3File(baseDir)
        if (target.exists() && target.length() == NAKARA_IPK3_SIZE &&
            sha256Hex(target) == NAKARA_IPK3_SHA256
        ) {
            return@withContext true
        }
        val pushed = File(ctx.getExternalFilesDir(null), NAKARA_IPK3_NAME)
        if (!pushed.exists() || pushed.canonicalPath == target.canonicalPath) {
            return@withContext false
        }
        // Wrong size: not our file, don't waste time hashing 651 MB.
        if (pushed.length() != NAKARA_IPK3_SIZE) return@withContext false
        if (sha256Hex(pushed) != NAKARA_IPK3_SHA256) return@withContext false
        target.parentFile?.mkdirs()
        target.delete()
        // Same volume: renameTo is instant. Fall back to a stream copy otherwise.
        if (pushed.renameTo(target)) return@withContext true
        try {
            pushed.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() == NAKARA_IPK3_SIZE && sha256Hex(target) == NAKARA_IPK3_SHA256) {
                pushed.delete()
                return@withContext true
            }
        } catch (e: IOException) {
            // Fall through to cleanup below.
        }
        target.delete()
        false
    }

/**
 * Imports Nk.ipk3 from a user-picked content [uri] (system file picker) into
 * [baseDir]: streams it to a `.part` file with progress callbacks,
 * SHA-256-verifies the result, and atomically renames it into place.
 *
 * @return true when a valid Nk.ipk3 is in place.
 * @throws IOException with a user-facing message when the copy or the hash
 *   check fails (the partial file is always cleaned up).
 */
suspend fun importNakaraFromUri(
    ctx: Context,
    uri: Uri,
    baseDir: String,
    onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    val target = nakaraIpk3File(baseDir)
    val part = File(target.path + ".part")
    target.parentFile?.mkdirs()
    part.delete()
    try {
        val total = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else -1L
        } ?: -1L
        var read = 0L
        onProgress(0L, total)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        } ?: throw IOException("Could not read the selected file.")
        if (sha256Hex(part) != NAKARA_IPK3_SHA256) {
            throw IOException(
                "That file is not the Nakara game data (integrity check failed). " +
                    "Make sure you picked the genuine Nk.ipk3.",
            )
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not install the game data file.")
        true
    } catch (e: IOException) {
        part.delete()
        throw e
    }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------
// Game-data UI
// ---------------------------------------------------------------------------

/**
 * Offline-first gate: shown when no valid Nk.ipk3 was found. The primary
 * action opens the system file picker so the user can point at the file
 * wherever it lives on the device; the Drive download is a secondary
 * fallback. Shown by the launch screen whenever [NakaraDataState] is [Missing].
 */
@Composable
fun NakaraMissingScreen(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                stringResource(R.string.nakara_missing_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.nakara_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nakara_find_file_button))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.nakara_sideload_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Progress / error gate: shown while checking, copying, or downloading, and
 * when one of those failed. Shown by the launch screen whenever
 * [NakaraDataState] isn't [Ready] or [Missing].
 */
@Composable
fun NakaraDownloadScreen(
    dataState: NakaraDataState,
    onPickFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            when (dataState) {
                is NakaraDataState.Checking -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.nakara_checking_data))
                }
                is NakaraDataState.Copying -> {
                    ProgressBlock(
                        title = stringResource(R.string.nakara_copying_title),
                        bytesRead = dataState.bytesRead,
                        totalBytes = dataState.totalBytes,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.nakara_copy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is NakaraDataState.Failed -> {
                    Text(
                        stringResource(R.string.nakara_download_failed_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        dataState.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nakara_find_file_button))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.back))
                    }
                }
                NakaraDataState.Ready, NakaraDataState.Missing -> Unit
            }
        }
    }
}

@Composable
private fun ProgressBlock(title: String, bytesRead: Long, totalBytes: Long) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(20.dp))
    if (totalBytes > 0) {
        LinearProgressIndicator(
            progress = { (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${formatFileSize(bytesRead)} of ${formatFileSize(totalBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            formatFileSize(bytesRead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
