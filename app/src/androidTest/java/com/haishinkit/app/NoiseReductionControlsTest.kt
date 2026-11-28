package com.haishinkit.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class NoiseReductionControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishControlsSwitchDryWetAndRestoreNormalInput() = exercise(Locale.ENGLISH, "noise-reduction-en.png")

    @Test fun japaneseControlsSwitchDryWetAndRestoreNormalInput() = exercise(Locale.JAPANESE, "noise-reduction-ja.png")

    @Test fun unavailableMicrophoneDisablesTheSwitchAndShowsFailure() {
        var label = ""
        compose.setContent {
            label = stringResource(R.string.rnnoise_title)
            StudioTheme {
                Surface {
                    NoiseReductionControls(false, true, false, "Microphone processing stopped", {}, {})
                }
            }
        }
        compose.onNodeWithContentDescription(label).assertIsNotEnabled()
        compose.onNodeWithText("Microphone processing stopped").assertIsDisplayed()
    }

    private fun exercise(
        locale: Locale,
        name: String,
    ) {
        var title = ""
        var restore = ""
        var detail = ""
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides localized) {
                var checked by remember { mutableStateOf(false) }
                var active by remember { mutableStateOf(false) }
                title = stringResource(R.string.rnnoise_title)
                restore = stringResource(R.string.rnnoise_restore_system)
                detail = stringResource(R.string.rnnoise_comparison_detail)
                StudioTheme {
                    Surface {
                        Box(Modifier.width(375.dp).height(320.dp)) {
                            NoiseReductionControls(checked, active, true, null, {
                                checked = it
                                active = true
                            }, {
                                checked = false
                                active = false
                            })
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(title).performClick().assertIsOn()
        compose.onNodeWithText(detail).assertIsDisplayed()
        compose.onNodeWithContentDescription(title).performClick()
        compose.onNodeWithText(restore).assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText(restore).performClick()
        compose.onNodeWithText(restore).assertDoesNotExist()
    }
}
