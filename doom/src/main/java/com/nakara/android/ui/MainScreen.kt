@file:OptIn(ExperimentalMaterial3Api::class)

package com.nakara.android.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.fragment.compose.AndroidFragment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.beloko.touchcontrols.GamePadFragment
import com.nakara.android.AppSettings
import com.nakara.android.R
import com.nakara.android.ui.controls.TouchControlsScreen
import com.nakara.android.ui.launch.LaunchScreen
import com.nakara.android.ui.launch.LaunchState
import com.nakara.android.ui.onboarding.OnboardingScreen
import com.nakara.android.ui.options.OptionsScreen
import com.nakara.android.ui.settings.AboutScreen
import com.nakara.android.ui.settings.SettingsScreen
import com.nakara.android.ui.stats.StatsScreen

private object Routes {
    const val PLAY = "play"
    const val SETTINGS = "settings"
    const val SETTINGS_TOUCH = "settings/touch"
    const val SETTINGS_GAMEPAD = "settings/gamepad"
    const val SETTINGS_OPTIONS = "settings/options"
    const val SETTINGS_STATS = "settings/stats"
    const val SETTINGS_ABOUT = "settings/about"
}

private data class BottomDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

@Composable
fun MainScreen() {
    val activity = requireNotNull(LocalActivity.current)

    // First-run wizard: shown once, before the main UI.
    var showOnboarding by remember {
        mutableStateOf(!AppSettings.getBoolOption(activity, "onboarding_done", false))
    }
    if (showOnboarding) {
        OnboardingScreen(onFinish = {
            AppSettings.setBoolOption(activity, "onboarding_done", true)
            showOnboarding = false
        })
        return
    }

    val launchState = remember { LaunchState(activity) }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Re-scan when returning to the PLAY destination (e.g. after the base dir changed in
    // Options). LaunchScreen's own one-shot initialize() won't re-run on a kept back-stack
    // entry, so this MainScreen-level effect restores the legacy rescan-on-return behavior.
    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.PLAY && launchState.initialized) {
            launchState.refreshGames()
            // Close any open play session started before the engine activity launched.
            com.nakara.android.ui.stats.StatsStore.finalizeSession(activity)
        }
    }

    val bottomDestinations = listOf(
        BottomDestination(Routes.PLAY, Icons.Filled.PlayArrow, R.string.play_tab),
        BottomDestination(Routes.SETTINGS, Icons.Filled.Settings, R.string.settings_tab),
    )

    Scaffold(
        // Edge-to-edge: content respects the safe area, but the NavigationBar paints its
        // surface behind the system navigation bar so the two share one color.
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { dest ->
                    // The Settings tab stays selected while drilled into its sub-pages.
                    val selected = currentRoute == dest.route ||
                        (dest.route == Routes.SETTINGS && currentRoute?.startsWith(Routes.SETTINGS) == true)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != dest.route) {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PLAY,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.PLAY) {
                LaunchScreen(
                    state = launchState,
                    onOpenOptions = { navController.navigate(Routes.SETTINGS_OPTIONS) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenTouchControls = { navController.navigate(Routes.SETTINGS_TOUCH) },
                    onOpenGamepad = { navController.navigate(Routes.SETTINGS_GAMEPAD) },
                    onOpenOptions = { navController.navigate(Routes.SETTINGS_OPTIONS) },
                    onOpenStats = { navController.navigate(Routes.SETTINGS_STATS) },
                    onReplayOnboarding = {
                        AppSettings.setBoolOption(activity, "onboarding_done", false)
                        showOnboarding = true
                    },
                    onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(Routes.SETTINGS_TOUCH) {
                SubPage(
                    title = stringResource(R.string.touch_controls_tab),
                    onBack = { navController.popBackStack() },
                ) { padding ->
                    TouchControlsScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
            composable(Routes.SETTINGS_GAMEPAD) {
                SubPage(
                    title = stringResource(R.string.gamepad_tab),
                    onBack = { navController.popBackStack() },
                ) { padding ->
                    AndroidFragment<GamePadFragment>(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
            composable(Routes.SETTINGS_OPTIONS) {
                SubPage(
                    title = stringResource(R.string.options_tab),
                    onBack = { navController.popBackStack() },
                ) { padding ->
                    OptionsScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
            composable(Routes.SETTINGS_STATS) {
                SubPage(
                    title = stringResource(R.string.stats_tab),
                    onBack = { navController.popBackStack() },
                ) { padding ->
                    StatsScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
            composable(Routes.SETTINGS_ABOUT) {
                SubPage(
                    title = stringResource(R.string.about_tab),
                    onBack = { navController.popBackStack() },
                ) { padding ->
                    AboutScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
            }
        }
    }
}

/** Full-screen Settings sub-page chrome: a top bar with a back arrow over [content]. */
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        // The parent Scaffold already insets this for the status bar via its innerPadding,
        // so this nested Scaffold must not add it again.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        content = content,
    )
}
