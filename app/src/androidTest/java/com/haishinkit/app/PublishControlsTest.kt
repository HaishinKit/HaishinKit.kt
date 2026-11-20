package com.haishinkit.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
        compose.onNodeWithText("GO LIVE").assertIsDisplayed()
        compose.onNodeWithText("Last camera setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GO LIVE").assertIsDisplayed()
        compose.onNodeWithText("Recording").assertIsDisplayed()
        compose.onNodeWithText("Screenshot").assertIsDisplayed()
    }
}
