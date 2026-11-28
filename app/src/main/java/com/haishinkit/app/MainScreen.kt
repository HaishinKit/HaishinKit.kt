package com.haishinkit.app

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

enum class MainScreenTab(
    val id: String,
    @param:StringRes val label: Int,
) {
    Camera("main/camera", R.string.studio_publish),
    Playback("main/playback", R.string.studio_playback),
    MediaProjection("main/mediaprojection", R.string.studio_share_tab),
    Preference("main/preference", R.string.studio_settings),
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TabIcon(tab: MainScreenTab) {
    when (tab) {
        MainScreenTab.Camera -> Icon(painterResource(R.drawable.videocam_24dp), contentDescription = null)
        MainScreenTab.MediaProjection -> Icon(painterResource(R.drawable.studio_screen), contentDescription = null)
        MainScreenTab.Playback -> Icon(Icons.Filled.PlayArrow, contentDescription = null)
        MainScreenTab.Preference -> Icon(Icons.Filled.Settings, contentDescription = null)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun MainScreen(
    onApplyHdr: suspend (HdrSettings) -> Unit = {},
    onApplyRenderer: suspend (Renderer) -> Unit,
) {
    val pictureInPicture = LocalContext.current.mainActivity()?.pictureInPicture == true
    var changingRenderer by remember { mutableStateOf(false) }
    var sceneFileBusy by remember { mutableStateOf(false) }
    val navigationBlocked = changingRenderer || sceneFileBusy
    BackHandler(enabled = navigationBlocked) {}
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val selectedRoute = entry?.destination?.route?.let { if (it == "main/codecs") MainScreenTab.Preference.id else it } ?: MainScreenTab.Camera.id
    val navigate: (MainScreenTab) -> Unit = { tab ->
        if (!changingRenderer && !sceneFileBusy) {
            navController.navigateToMainTab(tab)
        }
    }
    MainScreenLayout(pictureInPicture, navigationBlocked, selectedRoute, navigate) {
        NavHost(
            navController = navController,
            startDestination = MainScreenTab.Camera.id,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(MainScreenTab.Camera.id) { CameraScreen(onOpenSettings = { navigate(MainScreenTab.Preference) }, onSceneFileBusy = { sceneFileBusy = it }) }
            composable(MainScreenTab.MediaProjection.id) { MediaProjectionScreen(onOpenSettings = { navigate(MainScreenTab.Preference) }) }
            composable(MainScreenTab.Playback.id) { PlaybackScreen(onOpenSettings = { navigate(MainScreenTab.Preference) }) }
            composable("main/codecs") { DeviceCodecScreen(onBack = { navController.popBackStack() }) }
            composable(MainScreenTab.Preference.id) {
                PreferenceScreen(onApplyHdr = { settings ->
                    changingRenderer = true
                    try {
                        onApplyHdr(settings)
                    } finally {
                        changingRenderer = false
                    }
                }, onOpenCodecs = { if (!changingRenderer) navController.navigate("main/codecs") }, onApplyRenderer = { renderer ->
                    changingRenderer = true
                    try {
                        onApplyRenderer(renderer)
                    } finally {
                        changingRenderer = false
                    }
                })
            }
        }
    }
    if (changingRenderer) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Text(stringResource(R.string.studio_applying)) },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MainScreenLayout(
    pictureInPicture: Boolean,
    changingRenderer: Boolean,
    selectedRoute: String,
    onNavigate: (MainScreenTab) -> Unit,
    safeInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable () -> Unit,
) {
    val insets = if (pictureInPicture) WindowInsets(0.dp, 0.dp, 0.dp, 0.dp) else safeInsets
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal))) {
        val useRail = maxWidth >= 600.dp && !pictureInPicture
        Row(Modifier.fillMaxSize()) {
            if (useRail) {
                NavigationRail(windowInsets = insets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start), containerColor = MaterialTheme.colorScheme.surface) {
                    MainScreenTab.entries.forEach { tab ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("navigation-${tab.name}"),
                            selected = selectedRoute == tab.id,
                            onClick = { onNavigate(tab) },
                            icon = { TabIcon(tab) },
                            label = { Text(stringResource(tab.label)) },
                            enabled = !changingRenderer,
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!pictureInPicture && !useRail) {
                        NavigationBar(windowInsets = insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom), containerColor = MaterialTheme.colorScheme.surface) {
                            MainScreenTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    icon = { TabIcon(tab) },
                                    label = { Text(stringResource(tab.label)) },
                                    modifier = Modifier.testTag("navigation-${tab.name}"),
                                    selected = selectedRoute == tab.id,
                                    enabled = !changingRenderer,
                                    onClick = { onNavigate(tab) },
                                )
                            }
                        }
                    }
                },
                contentWindowInsets = insets,
            ) { padding ->
                Box(
                    Modifier.padding(padding).consumeWindowInsets(padding),
                ) {
                    content()
                }
            }
        }
    }
}

internal fun NavHostController.navigateToMainTab(tab: MainScreenTab) {
    navigate(tab.id) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    // Explicit Settings actions open the form, not a restored codec-detail destination.
    if (tab == MainScreenTab.Preference) popBackStack(MainScreenTab.Preference.id, false)
}
