package com.haishinkit.media

import com.haishinkit.screen.MockScreen
import com.haishinkit.screen.Screen
import com.haishinkit.stream.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AudioMixerFormatTest {
    @Test fun defaultStereoFormatAlsoReachesOutputsAddedThroughAStream() {
        val previous = Screen.factory
        Screen.factory = { MockScreen(it) }
        try {
            val context = RuntimeEnvironment.getApplication()
            val mixer = MediaMixer(context)
            val stream =
                object : Stream(context) {
                    override fun close() {}
                }
            mixer.registerOutput(stream)
            val recorder = MediaRecorder(context)
            stream.registerOutput(recorder)
            assertEquals(48000, stream.audioSetting.sampleRate)
            assertEquals(2, stream.audioSetting.channelCount)
            assertEquals(48000, recorder.audioSetting.sampleRate)
            assertEquals(2, recorder.audioSetting.channelCount)
            stream.unregisterOutput(recorder)
            mixer.unregisterOutput(stream)
        } finally {
            Screen.factory = previous
        }
    }

    @Test fun formatsAreAppliedToStreamsAndRecordersBeforeTheirCodecsStart() {
        val previous = Screen.factory
        Screen.factory = { MockScreen(it) }
        try {
            val context = RuntimeEnvironment.getApplication()
            val mixer = MediaMixer(context)
            mixer.audioMixerSettings = AudioMixerSettings(sampleRate = 44100, channels = 1)
            val stream =
                object : Stream(context) {
                    override fun close() {}
                }
            val recorder = MediaRecorder(context)
            mixer.registerOutput(stream)
            mixer.registerOutput(recorder)
            assertEquals(44100, stream.audioSetting.sampleRate)
            assertEquals(1, stream.audioSetting.channelCount)
            assertEquals(44100, recorder.audioSetting.sampleRate)
            assertEquals(1, recorder.audioSetting.channelCount)
            assertThrows(IllegalStateException::class.java) { mixer.audioMixerSettings = AudioMixerSettings() }
            mixer.audioMixerSettings = mixer.audioMixerSettings.copy(isMuted = true, tracks = mapOf(0 to AudioMixerTrackSettings(.5f)))
            mixer.unregisterOutput(stream)
            mixer.unregisterOutput(recorder)
            // Reusing an encoder with a changed format after stop/restart is deliberately rejected.
            assertThrows(IllegalStateException::class.java) { mixer.audioMixerSettings = AudioMixerSettings() }
        } finally {
            Screen.factory = previous
        }
    }
}
