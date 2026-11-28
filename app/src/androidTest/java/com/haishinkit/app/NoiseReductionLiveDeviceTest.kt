package com.haishinkit.app

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.source.AudioRecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in live microphone lifecycle test. No audio or video is saved or transmitted. */
class NoiseReductionLiveDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveSwitchAndRestartPreserveTheSelectedComparisonPath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noiseReductionLiveTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var model: CameraViewModel
        instrumentation.runOnMainSync {
            val application = instrumentation.targetContext.applicationContext as Application
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[CameraViewModel::class.java]
        }
        compose.setContent {
            StudioTheme {
                Surface {
                    NoiseReductionControls(model.noiseReductionEnabled, model.noiseComparisonActive, !model.noiseReductionBusy, model.noiseReductionError, model::setNoiseReductionEnabled, model::restoreSystemMicrophone)
                }
            }
        }
        val field = CameraViewModel::class.java.getDeclaredField("microphoneSource").apply { isAccessible = true }
        val microphone = field.get(model) as AudioRecordSource
        try {
            compose.runOnIdle {
                model.selectAudioDevice()
                model.startRunning()
            }
            compose.waitUntil(5000) { microphone.audioSessionId != null }
            val normalSession = microphone.audioSessionId
            compose.runOnIdle { model.setNoiseReductionEnabled(true) }
            compose.waitUntil(5000) { !model.noiseReductionBusy && model.noiseComparisonActive }
            assertTrue(model.noiseReductionEnabled)
            assertNull(model.noiseReductionError)
            val comparisonSession = microphone.audioSessionId
            assertNotEquals(normalSession, comparisonSession)
            compose.runOnIdle { model.setNoiseReductionEnabled(false) }
            compose.waitUntil(5000) { !model.noiseReductionBusy && !model.noiseReductionEnabled }
            assertTrue(model.noiseComparisonActive)
            assertEquals(comparisonSession, microphone.audioSessionId)
            compose.runOnIdle { model.selectAudioDevice() }
            compose.waitForIdle()
            assertEquals(comparisonSession, microphone.audioSessionId)
            compose.runOnIdle { model.stopRunning() }
            compose.waitUntil(5000) { microphone.audioSessionId == null }
            compose.runOnIdle {
                model.startRunning()
                model.refreshNoiseReductionStatus()
                assertNull(model.noiseReductionError)
            }
            compose.waitUntil(5000) { microphone.audioSessionId != null }
            compose.runOnIdle {
                model.refreshNoiseReductionStatus()
                assertNull(model.noiseReductionError)
            }
            assertTrue(model.noiseComparisonActive)
            assertTrue(!model.noiseReductionEnabled)
            compose.runOnIdle { model.restoreSystemMicrophone() }
            compose.waitUntil(5000) { !model.noiseReductionBusy && !model.noiseComparisonActive }
            assertNull(model.noiseReductionError)
        } finally {
            instrumentation.runOnMainSync {
                model.stopRunning()
                store.clear()
            }
        }
    }
}
