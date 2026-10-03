package com.nakara.android.ui.launch

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.util.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nakara.android.AppSettings
import com.nakara.android.Game
import com.nakara.android.R
import com.nakara.android.Utils
import com.nakara.android.ui.options.EngineOptions
import com.nakara.android.ui.options.buildEngineCvarArgs

/**
 * State holder for the launch tab. Durable state lives in [AppSettings] and the
 * args-history file; this class only mirrors it into Compose state.
 */
@Stable
class LaunchState(private val activity: Activity) {

    var games by mutableStateOf(listOf<WadEntry>())
        private set
    var selectedGame by mutableStateOf<WadEntry?>(null)
        private set
    var extraArgs by mutableStateOf("")
    val argsHistory = mutableStateListOf<String>()
    var isPreparing by mutableStateOf(false)
        private set
    var isLaunching by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set

    /** User-facing error from the unpack / launch paths; rendered as a dialog and dismissable. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /**
     * First-run game-data state. [NakaraDataState.Ready] means a valid Nk.ipk3 is in the
     * game-data directory; anything else gates the launch UI on the download screen.
     */
    var gameDataState by mutableStateOf<NakaraDataState>(NakaraDataState.Checking)
        private set

    val baseDir: String get() = AppSettings.getQuakeFullDir()

    fun dismissError() { errorMessage = null }

    /** First-run unpack + initial scan. Replaces the legacy 10-second restart hack. */
    suspend fun initialize() {
        if (initialized) return
        // A failed unpack used to launch the engine into a native crash with missing data;
        // now it surfaces an error and leaves [initialized] false so a retry re-runs.
        val firstRun = !File(baseDir, "firstrun").exists()
        if (firstRun) isPreparing = true
        try {
            withContext(Dispatchers.IO) {
                AppSettings.createDirectories(activity)
                Utils.copySupportFilesToSD(activity)
                if (firstRun) Utils.copyAsset(activity, "firstrun", baseDir)
            }
        } catch (e: IOException) {
            errorMessage = activity.getString(R.string.prepare_failed, e.message ?: e.toString())
            isPreparing = false
            return
        }
        isPreparing = false

        // Nakara game data, offline-first: a hash check decides. A valid Nk.ipk3
        // (installed before, picked from the device, or adb-pushed) goes
        // straight to Ready; otherwise the user is asked to provide the file.
        checkGameData()

        val history = ArrayList<String>()
        Utils.loadArgs(activity, history)
        argsHistory.clear()
        argsHistory.addAll(history)

        refreshGames()
        // Default to Nakara when there is no saved selection to restore.
        if (selectedGame == null) {
            games.find { it.file.equals(NAKARA_IPK3_NAME, ignoreCase = true) }?.let { selectGame(it) }
        }
        initialized = true
    }

    /**
     * Offline-first check: hashes the Nk.ipk3 in the game dir. Before giving up,
     * also looks for a file adb-pushed into the app's own external files dir
     * (the BlueStacks-friendly path — no file picker involved) and adopts it.
     * [NakaraDataState.Ready] when valid, [NakaraDataState.Missing] otherwise —
     * no network involved.
     */
    suspend fun checkGameData() {
        gameDataState = NakaraDataState.Checking
        gameDataState =
            if (hasValidNakaraIpk3(baseDir) || adoptPushedNakaraIpk3(activity, baseDir)) {
                NakaraDataState.Ready
            } else {
                NakaraDataState.Missing
            }
        if (gameDataState is NakaraDataState.Ready) {
            refreshGames()
            if (selectedGame == null) {
                games.find { it.file.equals(NAKARA_IPK3_NAME, ignoreCase = true) }
                    ?.let { selectGame(it) }
            }
        }
    }

    /**
     * Imports a user-picked file (system file picker) into the game dir,
     * driving [gameDataState] for the copy-progress UI.
     */
    suspend fun importGameData(uri: Uri) {
        gameDataState = NakaraDataState.Checking
        try {
            importNakaraFromUri(activity, uri, baseDir) { read, total ->
                gameDataState = NakaraDataState.Copying(read, total)
            }
            gameDataState = NakaraDataState.Ready
            refreshGames()
            if (selectedGame == null) {
                games.find { it.file.equals(NAKARA_IPK3_NAME, ignoreCase = true) }
                    ?.let { selectGame(it) }
            }
        } catch (e: IOException) {
            gameDataState = NakaraDataState.Failed(e.message ?: e.toString())
        }
    }


    /** Returns the gate to the missing-data screen (e.g. the Failed screen's Back button). */
    fun backToMissing() {
        gameDataState = NakaraDataState.Missing
    }

    suspend fun refreshGames() {
        // Scan order (filesystem order) is what the legacy int "last_iwad" indexed into.
        val scanOrder = withContext(Dispatchers.IO) { scanIwads(baseDir) }
        games = scanOrder.sortedBy { it.file.lowercase() }

        val lastName = AppSettings.getStringOption(activity, "last_iwad_name", null)
        val lastIndex = AppSettings.getIntOption(activity, "last_iwad", -1)
        selectedGame = games.find { it.file == lastName }
            ?: scanOrder.getOrNull(lastIndex)
    }

    fun selectGame(entry: WadEntry) {
        selectedGame = entry
        AppSettings.setIntOption(activity, "last_iwad", games.indexOf(entry))
        AppSettings.setStringOption(activity, "last_iwad_name", entry.file)
    }

    /**
     * Clears the game selection and the persisted last-IWAD prefs — the
     * escape hatch when a broken WAD would otherwise be auto-reselected every launch.
     * Keeps the extra-args field.
     */
    fun clearSelection() {
        selectedGame = null
        AppSettings.setIntOption(activity, "last_iwad", -1)
        AppSettings.setStringOption(activity, "last_iwad_name", "")
    }

    /** Ports LaunchFragmentGZdoom.startGame() — the Intent contract must stay identical. */
    suspend fun launchGame() {
        val game = selectedGame ?: return
        if (isLaunching) return
        val base = baseDir

        isLaunching = true
        try {
            withContext(Dispatchers.IO) {
                // UZDoom 5.0 (__MOBILE__) loads its base data from ./res relative to
                // the game dir: res/uzdoom.pk3 (BASEWAD) + res/uzdoom_game_support.pk3
                // (OPTIONALWAD, also carries IWADINFO). See engine src/version.h.
                Utils.copyAsset(activity, "uzdoom.pk3", "$base/res")
                Utils.copyAsset(activity, "uzdoom_game_support.pk3", "$base/res")
                Utils.copyAsset(activity, "game_widescreen_gfx.pk3", base)
                // Autoload extras are searched via $PROGDIR (= the game dir).
                Utils.copyAsset(activity, "lights.pk3", base)
                Utils.copyAsset(activity, "brightmaps.pk3", base)
                // Optional blood/particles add-on. The engine only autoloads the three
                // hardcoded pk3 names above, so this one is inert unless we pass it with
                // -file (done below when the toggle is on).
                Utils.copyAsset(activity, "extras_gore.pk3", base)
                // fluid_patchset gzdoom.sf2 resolves against the game dir; also
                // expose it in soundfonts/ for the engine's sound-font menu.
                Utils.copyAsset(activity, "gzdoom.sf2", base)
                Utils.copyAsset(activity, "gzdoom.sf2", "$base/soundfonts")
            }
        } catch (e: IOException) {
            errorMessage = activity.getString(R.string.launch_failed, e.message ?: e.toString())
            isLaunching = false
            return
        }
        isLaunching = false

        val trimmedArgs = extraArgs.trim()
        if (trimmedArgs.isNotEmpty()) {
            argsHistory.removeAll { it == trimmedArgs }
            while (argsHistory.size > 50) argsHistory.removeAt(argsHistory.size - 1)
            argsHistory.add(0, trimmedArgs)
            Utils.saveArgs(activity, ArrayList(argsHistory))
        }

        AppSettings.setStringOption(activity, "last_tab", "Nakara")

        // Optional add-on: append the bundled gore/particles pk3 via -file when enabled.
        // Composed here (not in buildLaunchArgs) so the byte-identical legacy command-line
        // contract and its golden test stay untouched. extras_gore.pk3 ships its own
        // CVAR defaults + options menu, so no +set cvars are forced.
        val goreArgs = if (AppSettings.getBoolOption(activity, "enable_gore_mod", false))
            "-file extras_gore.pk3 " else ""

        // Optional in-launcher engine settings (FOV, gamma, crosshair, volumes…) rendered
        // as +set/+fov args. Composed here (not in buildLaunchArgs) for the same reason as
        // goreArgs. Placed before extraArgs so a user-typed +set on the args field still wins.
        val engineArgs = if (AppSettings.getBoolOption(activity, EngineOptions.KEY_ENABLED, false))
            buildEngineCvarArgs(EngineOptions.fromPrefs(activity)) else ""

        // Begin a play session for the local stats screen; closed when the user returns
        // to the launcher (see MainScreen's PLAY-route effect).
        com.nakara.android.ui.stats.StatsStore.recordLaunch(activity)

        val intent = Intent(activity, Game::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("res_div", AppSettings.getIntOption(activity, "gzdoom_res_div", 1))
            putExtra("game_path", base)
            putExtra("game", "com.nakara.android")
            putExtra("args", buildLaunchArgs(game.iwadArgs, buildModArgs(emptyList()) + goreArgs + engineArgs, extraArgs, base))
        }
        activity.startActivity(intent)
    }
}
