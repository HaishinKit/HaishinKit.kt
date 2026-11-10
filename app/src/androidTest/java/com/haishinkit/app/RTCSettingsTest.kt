package com.haishinkit.app

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Verify the actual sample screens select RTC and retain endpoint/authentication settings. */
class RTCSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsOpenWhipPublishingAndWhepPlayback() {
        val previous = Preference.shared
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            automation.grantRuntimePermission("com.haishinkit.app", permission)
        }
        Preference.shared = Preference("rtmp://example.com/live", "test")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                compose.onNodeWithText("Publish URL (RTMP / SRT / WHIP)").performTextReplacement("http://127.0.0.1:8889/live/whip")
                compose.onNodeWithText("WHEP playback URL").performScrollTo().performTextReplacement("http://127.0.0.1:8889/live/whep")
                compose.onNodeWithText("Bearer token (optional)").performScrollTo().performTextReplacement("test-token")
                compose.onNodeWithText("STUN / TURN servers (optional)").performScrollTo().performTextReplacement("stun:example.com:3478")
                compose.runOnIdle {
                    val settings = Preference.shared.sessionSettings()
                    assertEquals("test-token", settings.rtc!!.bearerToken)
                    assertEquals(listOf("stun:example.com:3478"), settings.rtc.iceServers)
                }
                closeSoftKeyboard()
                compose.onNodeWithText("Publish").performClick()
                compose.onNodeWithText("GO LIVE").assertIsDisplayed()
                compose.onNodeWithText("Playback").performClick()
                compose.onNodeWithText("PLAY").assertIsDisplayed()
                compose.onNodeWithText("Preference").performClick()
                compose.onNodeWithText("http://127.0.0.1:8889/live/whip").performScrollTo().assertIsDisplayed()
            }
        } finally {
            Preference.shared = previous
        }
    }
}
