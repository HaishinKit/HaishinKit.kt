package com.haishinkit.app

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
fun MainScreen(onApplyRenderer: suspend (Renderer) -> Unit) {
    val pictureInPicture = LocalContext.current.mainActivity()?.pictureInPicture == true
    var changingRenderer by remember { mutableStateOf(false) }
    BackHandler(enabled = changingRenderer) {}
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val selectedRoute = entry?.destination?.route?.let { if (it == "main/codecs") MainScreenTab.Preference.id else it } ?: MainScreenTab.Camera.id
    val navigate: (MainScreenTab) -> Unit = { tab ->
        if (!changingRenderer) {
            navController.navigate(tab.id) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp && !pictureInPicture
        Row(Modifier.fillMaxSize()) {
            if (useRail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    MainScreenTab.entries.forEach { tab ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("navigation-${tab.name}"),
                            selected = selectedRoute == tab.id,
                            onClick = { navigate(tab) },
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
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            MainScreenTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    icon = { TabIcon(tab) },
                                    label = { Text(stringResource(tab.label)) },
                                    modifier = Modifier.testTag("navigation-${tab.name}"),
                                    selected = selectedRoute == tab.id,
                                    enabled = !changingRenderer,
                                    onClick = { navigate(tab) },
                                )
                            }
                        }
                    }
                },
                contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            ) { padding ->
                Box(
                    Modifier.padding(padding).consumeWindowInsets(padding),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = MainScreenTab.Camera.id,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                    ) {
                        composable(MainScreenTab.Camera.id) { CameraScreen(onOpenSettings = { navigate(MainScreenTab.Preference) }) }
                        composable(MainScreenTab.MediaProjection.id) { MediaProjectionScreen(onOpenSettings = { navigate(MainScreenTab.Preference) }) }
                        composable(MainScreenTab.Playback.id) { PlaybackScreen(onOpenSettings = { navigate(MainScreenTab.Preference) }) }
                        composable("main/codecs") { DeviceCodecScreen(onBack = { navController.popBackStack() }) }
                        composable(MainScreenTab.Preference.id) {
                            PreferenceScreen(onOpenCodecs = { if (!changingRenderer) navController.navigate("main/codecs") }, onApplyRenderer = { renderer ->
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
            }
        }
    }
}
