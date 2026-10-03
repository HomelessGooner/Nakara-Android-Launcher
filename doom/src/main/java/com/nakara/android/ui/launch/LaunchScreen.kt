package com.nakara.android.ui.launch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.nakara.android.R
import com.nakara.android.ui.DoomIcons
import com.nakara.android.ui.formatFileSize
import com.nakara.android.ui.theme.monospaceBody

// Below this width we stack into a single column with a pinned launch bar (portrait phones);
// at or above it we use the side-by-side two-pane layout (landscape / tablets).
private val WIDE_BREAKPOINT = 600.dp

@Composable
fun LaunchScreen(
    state: LaunchState,
    onOpenOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // System file picker for the offline-first flow: the user points at Nk.ipk3
    // wherever it lives on the device (Downloads, Drive app, USB storage…).
    // The hash check in importGameData is the real validation, so any type is allowed.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch { state.importGameData(uri) }
    }
    val onPickFile = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) }

    LaunchedEffect(Unit) { state.initialize() }

    if (state.isPreparing) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.preparing_game_data))
            }
        }
        return
    }

    // Nakara game-data gate, offline-first:
    // - Ready → the Play UI below.
    // - Missing → pick the file on this device.
    // - Anything else (checking / copying / failed) → progress or error UI.
    when (val dataState = state.gameDataState) {
        is NakaraDataState.Ready -> Unit
        is NakaraDataState.Missing -> {
            NakaraMissingScreen(
                onPickFile = onPickFile,
                modifier = modifier,
            )
            return
        }
        else -> {
            NakaraDownloadScreen(
                dataState = dataState,
                onPickFile = onPickFile,
                onBack = { state.backToMissing() },
                modifier = modifier,
            )
            return
        }
    }

    val onRefresh = { scope.launch { state.refreshGames() }; Unit }
    val onLaunch = { scope.launch { state.launchGame() }; Unit }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= WIDE_BREAKPOINT) {
            WideLayout(state, onLaunch, onRefresh, onOpenOptions)
        } else {
            CompactLayout(state, onLaunch, onRefresh, onOpenOptions)
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { state.dismissError() },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { state.dismissError() }) {
                    Text(stringResource(R.string.ok_confirm))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Layouts
// ---------------------------------------------------------------------------

@Composable
private fun WideLayout(
    state: LaunchState,
    onLaunch: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(0.5f)) {
            GameListHeader(state)
            Spacer(Modifier.height(4.dp))
            if (state.games.isEmpty()) {
                EmptyGamesState(state.baseDir, onRefresh, onOpenOptions)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.games, key = { it.file }) { game ->
                        GameCard(game, game == state.selectedGame) { state.selectGame(game) }
                    }
                }
            }
        }
        LaunchPane(
            state = state,
            onLaunch = onLaunch,
            modifier = Modifier.weight(0.5f),
        )
    }
}

@Composable
private fun CompactLayout(
    state: LaunchState,
    onLaunch: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding(),
    ) {
        GameListHeader(state)
        Spacer(Modifier.height(4.dp))

        if (state.games.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyGamesState(state.baseDir, onRefresh, onOpenOptions)
            }
            return@Column
        }

        // One scroll container for cards + the launch panel; PLAY is pinned below it.
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.games, key = { it.file }) { game ->
                GameCard(game, game == state.selectedGame) { state.selectGame(game) }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    state.selectedGame?.file ?: stringResource(R.string.select_game_to_launch),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.selectedGame != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                ExtraArgsField(state)
            }
        }
        Spacer(Modifier.height(12.dp))
        LaunchButton(state, onLaunch)
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun GameListHeader(state: LaunchState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.select_game_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { state.clearSelection() },
            enabled = state.selectedGame != null,
        ) {
            Text(stringResource(R.string.reset_button_text))
        }
    }
}

@Composable
private fun GameCard(game: WadEntry, selected: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "gameCardColor",
    )
    Card(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                DoomIcons.Gamepad,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(game.file, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatFileSize(game.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmptyGamesState(baseDir: String, onRefresh: () -> Unit, onOpenOptions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            DoomIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.no_wads_found), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.no_iwads_err) + baseDir,
            style = monospaceBody(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.refresh_button))
            }
            OutlinedButton(onClick = onOpenOptions) {
                Icon(Icons.Default.Settings, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.open_options_button))
            }
        }
    }
}

@Composable
private fun LaunchPane(
    state: LaunchState,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                state.selectedGame?.file ?: stringResource(R.string.select_game_to_launch),
                style = MaterialTheme.typography.headlineSmall,
                color = if (state.selectedGame != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            ExtraArgsField(state)
        }

        Spacer(Modifier.height(12.dp))
        LaunchButton(state, onLaunch)
    }
}

@Composable
private fun LaunchButton(state: LaunchState, onLaunch: () -> Unit) {
    Button(
        onClick = onLaunch,
        enabled = state.selectedGame != null && !state.isLaunching,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (state.isLaunching) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.launching_game), style = MaterialTheme.typography.titleMedium)
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.start_full), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (state.selectedGame == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.launch_disabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExtraArgsField(state: LaunchState) {
    var historyExpanded by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = state.extraArgs,
        onValueChange = { state.extraArgs = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.extra_args_label)) },
        textStyle = monospaceBody(),
        singleLine = true,
        trailingIcon = {
            Row {
                if (state.extraArgs.isNotEmpty()) {
                    IconButton(onClick = { state.extraArgs = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_args))
                    }
                }
                if (state.argsHistory.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { historyExpanded = true }) {
                            Icon(DoomIcons.History, contentDescription = stringResource(R.string.args_history))
                        }
                        DropdownMenu(
                            expanded = historyExpanded,
                            onDismissRequest = { historyExpanded = false },
                        ) {
                            state.argsHistory.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry, style = monospaceBody()) },
                                    onClick = {
                                        state.extraArgs = entry
                                        historyExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

