package com.haishinkit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.stream.StreamSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PublishControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun adjustmentsStayInSheetAndPrimaryActionsReturnAfterDismissal() {
        compose.setContent {
            StudioTheme {
                PublishControls(
                    modifier = Modifier.height(280.dp),
                    settings = {
                        Text("Camera selector")
                        Box(Modifier.height(400.dp))
                        Text("Last camera setting")
                    },
                    actions = { CameraControllerView(false, false, {}, {}, {}) },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText("Camera selector").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.studio_camera_tools)).performClick()
        compose.onNodeWithText("Last camera setting").performScrollTo().assertIsDisplayed()
        pressBack()
        compose.onNodeWithText(context.getString(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.studio_record)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.studio_snapshot)).assertIsDisplayed()
    }

    @Test
    fun publishingActionsRemainVisibleWithLargeTextInShortWindows() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                StudioTheme {
                    PublishControls(
                        modifier = Modifier.height(280.dp),
                        settings = { Text("Camera tools") },
                        actions = { CameraControllerView(false, false, {}, {}, {}) },
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.studio_record)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.studio_snapshot)).assertIsDisplayed()
    }

    @Test
    fun centerOfPreviewRemainsAvailableForCompositionEditing() {
        var previewTaps = 0
        compose.setContent {
            StudioTheme {
                Box(Modifier.height(400.dp)) {
                    Box(Modifier.fillMaxSize().testTag("camera-preview").clickable { previewTaps++ })
                    PublishControls(
                        modifier = Modifier.fillMaxSize(),
                        settings = { Text("Hidden adjustments") },
                        actions = { CameraControllerView(false, false, {}, {}, {}) },
                    )
                }
            }
        }
        compose.onNodeWithTag("camera-preview").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, previewTaps) }
    }

    @Test
    fun compactStreamActionKeepsCancellationAndClosingBehavior() {
        var taps = 0
        val state = mutableStateOf(StreamSession.ReadyState.CONNECTING)
        compose.setContent {
            StudioTheme {
                CameraControllerView(false, true, {}, { taps++ }, {}, readyState = state.value, canCancelConnection = true)
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.studio_cancel)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, taps)
            state.value = StreamSession.ReadyState.CLOSING
        }
        compose.onNodeWithText(context.getString(R.string.studio_stopping)).assertIsNotEnabled()
    }
}
