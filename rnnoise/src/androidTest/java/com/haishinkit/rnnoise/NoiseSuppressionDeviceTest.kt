package com.haishinkit.rnnoise

import android.content.Intent
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in session-state checks. Captured PCM is never saved or transmitted. */
@RunWith(AndroidJUnit4::class)
class NoiseSuppressionDeviceTest {
    @Test fun microphoneInputsConfirmOsSuppressionIsOffBeforeProcessing() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("noiseSuppressionDeviceTest") == "true")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            context.startActivity(Intent(context, NoiseSuppressionTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var foreground = false
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!foreground && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    foreground = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is NoiseSuppressionTestActivity }
                }
                SystemClock.sleep(50)
            }
            val mixer = MediaMixer(context)
            try {
                assertTrue("Microphone diagnostic is not foreground", foreground)
                for (audioSource in intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
                    val source =
                        AudioRecordSource(context).apply {
                            sampleRate = 48000
                            this.audioSource = audioSource
                        }
                    val input = RnnoiseMicrophoneSource(source)
                    try {
                        source.open(mixer).getOrThrow()
                        val initial = source.audioSessionId?.let { NoiseSuppressor.create(it) }
                        val defaultState = initial?.enabled
                        initial?.release()
                        source.close().getOrThrow()
                        input.open(mixer).getOrThrow()
                        var frames = 0
                        repeat(5) { frames += (input.read(0).payload?.remaining() ?: 0) / 2 }
                        assertTrue(frames > 0)
                        val state = input.osNoiseSuppression
                        assertTrue(state != RnnoiseMicrophoneSource.OsNoiseSuppression.UNCONFIRMED)
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString("stream", "\nRNNOISE_OS_NS source=$audioSource default=$defaultState confirmed=$state rate=${input.audioFormat.sampleRate} frames=$frames\n")
                            },
                        )
                        // Simulate the session effect becoming enabled again after a route change.
                        val field = RnnoiseMicrophoneSource::class.java.getDeclaredField("effect").apply { isAccessible = true }
                        val effect = field.get(input) as? NoiseSuppressor
                        if (effect != null && effect.hasControl()) {
                            assertEquals(0, effect.setEnabled(true))
                            assertThrows(IllegalStateException::class.java) { input.read(0) }
                            assertEquals(RnnoiseMicrophoneSource.OsNoiseSuppression.UNCONFIRMED, input.osNoiseSuppression)
                            input.close().getOrThrow()
                            input.open(mixer).getOrThrow()
                            assertTrue(input.read(0).payload!!.hasRemaining())
                            assertEquals(RnnoiseMicrophoneSource.OsNoiseSuppression.DISABLED, input.osNoiseSuppression)
                        }
                    } finally {
                        input.close()
                    }
                }
            } finally {
                mixer.dispose()
                instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<NoiseSuppressionTestActivity>().forEach { it.finish() }
                }
            }
        }
}
