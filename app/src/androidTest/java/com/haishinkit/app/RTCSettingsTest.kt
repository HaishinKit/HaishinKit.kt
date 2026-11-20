package com.haishinkit.app

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
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
                compose.onNodeWithTag("navigation-Preference").performClick()
                compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_url)).performTextReplacement("http://127.0.0.1:8889/live/whip")
                compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_whep)).performScrollTo().performTextReplacement("http://127.0.0.1:8889/live/whep")
                compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_token)).performScrollTo().performTextReplacement("test-token")
                compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_ice)).performScrollTo().performTextReplacement("stun:example.com:3478")
                compose.runOnIdle {
                    val settings = Preference.shared.sessionSettings()
                    assertEquals("test-token", settings.rtc!!.bearerToken)
                    assertEquals(listOf("stun:example.com:3478"), settings.rtc.iceServers)
                }
                closeSoftKeyboard()
                compose.onNodeWithTag("navigation-Camera").performClick()
                compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_go_live)).assertIsDisplayed()
                compose.onNodeWithTag("navigation-Playback").performClick()
                compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.studio_play)).assertIsDisplayed()
                compose.onNodeWithTag("navigation-Preference").performClick()
                compose.onNodeWithText("http://127.0.0.1:8889/live/whip").performScrollTo().assertIsDisplayed()
                pressBack()
                compose.onNodeWithTag("navigation-Camera").assertIsSelected()
            }
        } finally {
            Preference.shared = previous
        }
    }

    @Test
    fun invalidConnectionCanBeCorrectedWithoutLeavingTheApp() {
        val previous = Preference.shared
        Preference.shared = Preference("invalid-url", "test")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("navigation-Preference").performClick()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                compose.onNodeWithText(context.getString(R.string.studio_url_error)).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.studio_url)).performTextReplacement("https://example.com/whip")
                closeSoftKeyboard()
                compose.onNodeWithTag("navigation-Camera").performClick()
                compose.onNodeWithText(context.getString(R.string.studio_go_live)).assertIsDisplayed()
            }
        } finally {
            Preference.shared = previous
        }
    }
}
