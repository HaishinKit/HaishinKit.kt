package com.haishinkit.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.DynamicRangeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class HdrControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val previous = Preference.shared
    private val previousHdr = HdrPreference.selected(context)

    @Before fun prepare() {
        Preference.shared = Preference("rtmp://127.0.0.1/live", "test")
        HdrPreference.save(context, HdrSettings())
    }

    @After fun restore() {
        Preference.shared = previous
        HdrPreference.save(context, previousHdr)
    }

    @Test fun publishingAndPlaybackCanBeSelectedIndependentlyBeforeApplying() {
        var applied: HdrSettings? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StudioTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HdrSettingsControls { applied = it }
                }
            }
        }
        compose.onNodeWithTag("hdr-apply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("hdr-apply").assertTextContains(context.getString(R.string.studio_hdr_apply))
        compose.onNodeWithTag("hdr-publish-HLG").performScrollTo().performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("hdr-publish-HLG").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("hdr-playback-SDR").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("hdr-playback-HLG").performScrollTo().performClick().assertIsSelected()
        if (InstrumentationRegistry.getArguments().getString("captureHdrUi") == "true") {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(context.filesDir, "hdr-controls.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("hdr-apply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.HLG), applied) }
    }

    @Test fun unsupportedTransportDisablesHlgChoices() {
        Preference.shared = Preference("srt://127.0.0.1:9999", "")
        compose.setContent {
            StudioTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { HdrSettingsControls {} }
            }
        }
        compose.onNodeWithTag("hdr-publish-HLG").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("hdr-playback-HLG").performScrollTo().assertIsNotEnabled()
    }
}
