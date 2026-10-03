package com.nakara.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nakara.android.ui.launch.NAKARA_IPK3_NAME
import com.nakara.android.ui.launch.NAKARA_IPK3_SHA256
import com.nakara.android.ui.launch.NAKARA_IPK3_SIZE
import com.nakara.android.ui.launch.nakaraIpk3File
import com.nakara.android.ui.launch.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Boot loader for Nakara.
 *
 * Two build flavors (see doom/build.gradle.kts):
 *
 * - "bundled" (dev build): Nk.ipk3 ships inside the APK (assets/Nk.ipk3,
 *   ~651 MB) and is copied to the game directory on first run. One-file
 *   install for development and testing.
 *
 * - "launcher" (public build, [BuildConfig.DATA_LESS]): the APK contains no
 *   game data at all. On first run the user picks their own legally obtained
 *   Nk.ipk3 via the system file picker; the app verifies it really is the
 *   Nakara archive, applies the Android compatibility patch (see
 *   [patchZscript]/[patchMapinfo]/[patchKeyconf] — the patch content ships
 *   as assets/compat/) to their copy, and boots. Later launches hash-check
 *   the patched copy against the values recorded at import.
 *
 * Either way, once a valid Nk.ipk3 is in the game directory this activity
 * launches the Game activity directly and finishes — no launcher UI.
 */
class EntryActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var pickButton: Button

    private val pickIpk3 =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pickButton.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        importAndPatch(uri)
                    } catch (e: Exception) {
                        showImportError("Import failed: ${e.message}")
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal programmatic UI: boot progress, or the first-run import gate.
        val layout = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        statusText = TextView(this).apply {
            text = "Loading Nakara..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                bottomMargin = 80
            }
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = 64
                rightMargin = 64
                topMargin = 80
            }
        }
        pickButton = Button(this).apply {
            text = "Select Nk.ipk3"
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 120
            }
            setOnClickListener {
                pickIpk3.launch(arrayOf("*/*"))
            }
        }
        layout.addView(statusText)
        layout.addView(progressBar)
        layout.addView(pickButton)
        setContentView(layout)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                boot()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private suspend fun boot() {
        // Initialize settings first — this sets gameBaseDir from prefs or external storage.
        // Without this, getQuakeFullDir() returns "null/config".
        AppSettings.reloadSettings(application)

        // Create directories (app-specific external storage, no permission needed).
        AppSettings.createDirectories(application)
        val baseDir = AppSettings.getQuakeFullDir()
        File(baseDir).mkdirs()

        // Copy engine base assets (required by UZDoom 5.0 — it loads ./res/uzdoom.pk3
        // relative to the game dir). These were copied by the old launcher's launchGame().
        // Only copy if missing or size differs to avoid redundant 100MB+ copies.
        copyEngineAssets(baseDir)

        // Export any ZScript error log from the last run.
        if (ErrorLogExporter.exportIfPresent(application)) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    application,
                    "ZScript error log saved to Downloads",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        if (BuildConfig.DATA_LESS) {
            bootLauncher(baseDir)
        } else {
            bootBundled(baseDir)
        }
    }

    /** Dev build: Nk.ipk3 comes from the APK's assets. */
    private suspend fun bootBundled(baseDir: String) {
        val target = nakaraIpk3File(baseDir)
        if (!isValidBundledIpk3(target)) {
            withContext(Dispatchers.Main) {
                statusText.text = "Installing game data (651 MB)...\nThis only happens once."
                progressBar.isIndeterminate = false
            }
            copyFromAssets(target)
            if (!isValidBundledIpk3(target)) {
                throw Exception("Game data install failed (hash mismatch)")
            }
        }
        launchGame(baseDir)
    }

    /**
     * Public launcher: Nk.ipk3 must have been imported and patched by the
     * user (see [importAndPatch]). The import records the patched file's
     * size + SHA-256 in prefs; a match here boots straight into the game.
     */
    private suspend fun bootLauncher(baseDir: String) {
        val target = nakaraIpk3File(baseDir)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val expectedSize = prefs.getLong(KEY_IPK3_SIZE, -1L)
        val expectedSha = prefs.getString(KEY_IPK3_SHA, null)
        if (target.exists() && expectedSha != null &&
            target.length() == expectedSize && sha256Hex(target) == expectedSha
        ) {
            launchGame(baseDir)
            return
        }
        withContext(Dispatchers.Main) {
            statusText.text =
                "This launcher contains no game data.\n\n" +
                    "Nakara is a commercial game — buy it on itch.io, DLsite or " +
                    "Steam, copy Nk.ipk3 to this phone, then select it here.\n\n" +
                    "The Android compatibility patch is applied to your copy " +
                    "automatically (needs ~1.3 GB free during setup)."
            progressBar.visibility = View.GONE
            pickButton.visibility = View.VISIBLE
        }
    }

    private suspend fun launchGame(baseDir: String) {
        withContext(Dispatchers.Main) {
            statusText.text = "Starting..."
            val args = "-iwad $NAKARA_IPK3_NAME " +
                "-savedir $baseDir/gzdoom_saves " +
                "+set fluid_patchset gzdoom.sf2 +set midi_dmxgus 0 "
            val intent = Intent(this@EntryActivity, Game::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("res_div", 1)
                putExtra("game_path", baseDir)
                putExtra("game", packageName)
                putExtra("args", args)
            }
            startActivity(intent)
            finish()
        }
    }

    // ------------------------------------------------------------------
    // Launcher import: copy the picked file in, validate it is the Nakara
    // archive, apply the compat patch, record its hash, boot.
    // ------------------------------------------------------------------

    private suspend fun importAndPatch(uri: Uri) {
        val baseDir = AppSettings.getQuakeFullDir()
        val target = nakaraIpk3File(baseDir)
        val tmp = File(cacheDir, "nakara_import.ipk3")
        val part = File(target.path + ".part")
        try {
            copyPickedFile(uri, tmp)
            patchInto(tmp, part)
            target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Could not install the patched game data.")
            }
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_IPK3_SIZE, target.length())
                .putString(KEY_IPK3_SHA, sha256Hex(target))
                .apply()
            tmp.delete()
            launchGame(baseDir)
        } finally {
            tmp.delete()
            part.delete()
        }
    }

    private suspend fun copyPickedFile(uri: Uri, tmp: File) {
        withContext(Dispatchers.Main) {
            statusText.text = "Copying your Nk.ipk3..."
            progressBar.isIndeterminate = false
        }
        val total = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else -1L
        } ?: -1L
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                var lastProgress = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (total > 0) {
                        val progress = (copied * 100 / total).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) { progressBar.progress = progress }
                        }
                    }
                }
            }
        } ?: throw IOException("Could not read the selected file.")
    }

    /**
     * Validates [source] is the Nakara archive (the three patchable text
     * lumps must be present) and writes the patched archive to [dest],
     * replacing zscript.zsc / mapinfo.txt / keyconf.txt with patched
     * versions of the user's own files. Every other entry is copied through.
     *
     * Patching (not whole-file replacement) keeps this build honest: the APK
     * ships only our own fix text, never any of the game's files.
     */
    private suspend fun patchInto(source: File, dest: File) {
        withContext(Dispatchers.Main) {
            statusText.text = "Applying Android compatibility patch..."
            progressBar.progress = 0
        }
        val fixBytes = assets.open("compat/zscript_fix.zsc").use { it.readBytes() }
        ZipFile(source).use { zip ->
            val names = zip.entries().asSequence().map { it.name.lowercase() }.toHashSet()
            for (required in listOf("zscript.zsc", "mapinfo.txt", "keyconf.txt")) {
                if (required !in names) {
                    throw IOException(
                        "That file is not Nakara's Nk.ipk3 (missing $required). " +
                            "Pick the Nk.ipk3 from your purchased copy of the game."
                    )
                }
            }
            val totalBytes = zip.entries().asSequence().sumOf { it.size }
            var doneBytes = 0L
            var lastProgress = -1
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(FileOutputStream(dest), digest).use { fileOut ->
                ZipOutputStream(fileOut).use { zipOut ->
                    zipOut.setLevel(1)
                    val buffer = ByteArray(256 * 1024)
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zipOut.putNextEntry(
                            java.util.zip.ZipEntry(entry.name).apply { time = entry.time }
                        )
                        if (!entry.isDirectory) {
                            val patched = when (entry.name.lowercase()) {
                                "zscript.zsc" -> patchZscript(readEntry(zip, entry), fixBytes)
                                "mapinfo.txt" -> patchMapinfo(readEntry(zip, entry))
                                "keyconf.txt" -> patchKeyconf(readEntry(zip, entry))
                                else -> null
                            }
                            if (patched != null) {
                                zipOut.write(patched)
                            } else {
                                zip.getInputStream(entry).use { input ->
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        zipOut.write(buffer, 0, n)
                                    }
                                }
                            }
                        }
                        zipOut.closeEntry()
                        doneBytes += entry.size
                        if (totalBytes > 0) {
                            val progress = (doneBytes * 100 / totalBytes).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { progressBar.progress = progress }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray =
        zip.getInputStream(entry).use { it.readBytes() }

    /** Appends the Android fix classes unless a previous patch added them. */
    private fun patchZscript(data: ByteArray, fix: ByteArray): ByteArray {
        val text = String(data, Charsets.ISO_8859_1)
        if (text.contains("NakaraGalleryHandler")) return data
        return data + fix
    }

    /** Registers NakaraGalleryHandler in GameInfo's AddEventHandlers list. */
    private fun patchMapinfo(data: ByteArray): ByteArray {
        val text = String(data, Charsets.ISO_8859_1)
        if (text.contains("NakaraGalleryHandler")) return data
        val lines = text.split("\n")
        val out = StringBuilder()
        var done = false
        for ((i, line) in lines.withIndex()) {
            var l = line
            if (!done && l.contains("AddEventHandlers")) {
                l = l.trimEnd(' ', '\t', '\r') + ",\"NakaraGalleryHandler\""
                done = true
            }
            out.append(l)
            if (i < lines.size - 1) out.append("\n")
        }
        if (!done) {
            throw IOException("Unsupported Nk.ipk3: MAPINFO has no AddEventHandlers entry.")
        }
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** Repoints the gallery key bind to K and arms the gallery command. */
    private fun patchKeyconf(data: ByteArray): ByteArray {
        var text = String(data, Charsets.ISO_8859_1)
        if (!text.contains("pukename GalleryChecks")) {
            text = text.replace(
                "defaultbind f CloseGallerymenu",
                "defaultbind k CloseGallerymenu",
            )
            text = text.replace(
                "alias CloseGallerymenu \"\"",
                "alias CloseGallerymenu \"pukename GalleryChecks\"",
            )
        }
        if (!text.contains("bind j +attack")) {
            text += "defaultbind c +crouch\nbind j +attack\n"
        }
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    private suspend fun showImportError(message: String) {
        withContext(Dispatchers.Main) {
            statusText.text = message + "\n\nTap below to pick a different file."
            progressBar.visibility = View.GONE
            pickButton.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------
    // Bundled (dev) helpers — unchanged behavior.
    // ------------------------------------------------------------------

    private fun isValidBundledIpk3(file: File): Boolean {
        return file.exists() &&
            file.length() == NAKARA_IPK3_SIZE &&
            sha256Hex(file) == NAKARA_IPK3_SHA256
    }

    private suspend fun copyFromAssets(target: File) {
        target.parentFile?.mkdirs()
        assets.open(NAKARA_IPK3_NAME).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024 * 1024) // 8MB buffer
                var total: Long = 0
                var read: Int
                var lastProgress = -1
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    total += read
                    val progress = (total * 100 / NAKARA_IPK3_SIZE).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        withContext(Dispatchers.Main) {
                            progressBar.progress = progress
                            statusText.text = "Installing game data...\n$progress% ($total / $NAKARA_IPK3_SIZE bytes)"
                        }
                    }
                }
            }
        }
    }

    /**
     * Copies UZDoom engine base assets from APK assets to the game directory.
     * The engine loads ./res/uzdoom.pk3 relative to the game dir — without these,
     * it fails with "Cannot find ./res/uzdoom.pk3". Skips files that already exist
     * with matching size to avoid redundant copies on every launch.
     */
    private suspend fun copyEngineAssets(baseDir: String) {
        withContext(Dispatchers.Main) {
            statusText.text = "Preparing engine..."
        }
        val assetsToCopy = listOf(
            "uzdoom.pk3" to "$baseDir/res",
            "uzdoom_game_support.pk3" to "$baseDir/res",
            "game_widescreen_gfx.pk3" to baseDir,
            "lights.pk3" to baseDir,
            "brightmaps.pk3" to baseDir,
            "gzdoom.sf2" to baseDir,
            "gzdoom.sf2" to "$baseDir/soundfonts"
        )
        for ((assetName, destDir) in assetsToCopy) {
            val destFile = File(destDir, assetName)
            // Skip if exists with non-zero size (assets don't change between launches)
            if (destFile.exists() && destFile.length() > 0) continue
            try {
                File(destDir).mkdirs()
                assets.open(assetName).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                // Non-critical assets (e.g. soundfont) shouldn't block boot;
                // uzdoom.pk3 is critical and will fail naturally if missing.
                if (assetName == "uzdoom.pk3") throw e
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "nakara_launcher"
        const val KEY_IPK3_SIZE = "ipk3_size"
        const val KEY_IPK3_SHA = "ipk3_sha256"
    }
}
