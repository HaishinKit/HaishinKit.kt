package com.haishinkit.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

enum class MainScreenTab(
    val id: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
) {
    Camera(
        id = "main/camera",
        icon = Icons.Outlined.Face,
        selectedIcon = Icons.Filled.Face,
        "Camera",
    ),
    Playback(
        id = "main/playback",
        icon = Icons.Outlined.PlayArrow,
        selectedIcon = Icons.Filled.PlayArrow,
        "Playback",
    ),
    MediaProjection(
        id = "main/mediaprojection",
        icon = Icons.Outlined.Build,
        selectedIcon = Icons.Filled.Build,
        "MediaProjection",
    ),
    Preference(
        id = "main/preference",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
        "Preference",
    ),
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    var selectedItemIndex by rememberSaveable {
        mutableIntStateOf(0)
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainScreenTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = {
                            if (selectedItemIndex == index) {
                                Icon(item.selectedIcon, contentDescription = item.label)
                            } else {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        },
                        label = { Text(text = item.label, fontSize = 10.sp) },
                        selected = selectedItemIndex == index,
                        onClick = {
                            selectedItemIndex = index
                            navController.navigate(item.id)
                        },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(
                        WindowInsets.safeContent.only(
                            WindowInsetsSides.Bottom,
                        ),
                    ),
        ) {
            NavHost(
                navController = navController,
                startDestination = MainScreenTab.Camera.id,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                composable(route = MainScreenTab.Camera.id) {
                    CameraScreen()
                }
                composable(route = MainScreenTab.MediaProjection.id) {
                    MediaProjectionScreen()
                }
                composable(route = MainScreenTab.Playback.id) {
                    PlaybackScreen()
                }
                composable(route = MainScreenTab.Preference.id) {
                    PreferenceScreen()
                }
            }
        }
    }
}
