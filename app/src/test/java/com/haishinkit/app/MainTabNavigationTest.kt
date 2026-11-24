package com.haishinkit.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class MainTabNavigationTest {
    @Test
    fun explicitSettingsReturnsToFormAfterLeavingCodecDetails() {
        withController { controller ->
            controller.navigateToMainTab(MainScreenTab.Preference)
            controller.navigate("main/codecs")
            controller.navigateToMainTab(MainScreenTab.Playback)
            controller.navigateToMainTab(MainScreenTab.Preference)
            assertEquals(MainScreenTab.Preference.id, controller.currentDestination?.route)
        }
    }

    @Test
    fun reselectingSettingsClosesItsCodecChild() {
        withController { controller ->
            controller.navigateToMainTab(MainScreenTab.Preference)
            controller.navigate("main/codecs")
            controller.navigateToMainTab(MainScreenTab.Preference)
            assertEquals(MainScreenTab.Preference.id, controller.currentDestination?.route)
        }
    }

    private fun withController(action: (NavHostController) -> Unit) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val store = ViewModelStore()
        val controller =
            NavHostController(RuntimeEnvironment.getApplication()).apply {
                setViewModelStore(store)
                setLifecycleOwner(owner)
                navigatorProvider.addNavigator(ComposeNavigator())
                graph =
                    createGraph(startDestination = MainScreenTab.Camera.id) {
                        MainScreenTab.entries.forEach { tab -> composable(tab.id) {} }
                        composable("main/codecs") {}
                    }
            }
        try {
            action(controller)
        } finally {
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
