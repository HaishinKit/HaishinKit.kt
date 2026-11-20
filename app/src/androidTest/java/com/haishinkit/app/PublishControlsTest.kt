package com.haishinkit.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class PublishControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun publishingActionsStayVisibleWhenSettingsExceedLandscapeHeight() {
        compose.setContent {
            PublishControls(
                modifier = Modifier.height(280.dp),
                settings = {
                    Text("Camera selector")
                    Box(Modifier.height(400.dp))
                    Text("Last camera setting")
                },
                actions = {
                    CameraControllerView(
                        isRecording = false,
                        isConnected = false,
                        onClickScreenShot = {},
                        onClickConnect = {},
                        onClickRecording = {},
                    )
                },
            )
        }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithText("Last camera setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_record)).assertIsDisplayed()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_snapshot)).assertIsDisplayed()
    }

    @Test
    fun publishingActionsRemainVisibleWithLargeTextInShortWindows() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StudioTheme {
                    PublishControls(
                        modifier = Modifier.height(280.dp),
                        settings = {
                            Text("Camera tools")
                            Box(Modifier.height(400.dp))
                            Text("Last camera setting")
                        },
                        actions = {
                            CameraControllerView(false, false, {}, {}, {})
                        },
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.studio_record)).assertIsDisplayed()
        compose.onNodeWithText("Last camera setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.studio_go_live)).assertIsDisplayed()
    }
}
