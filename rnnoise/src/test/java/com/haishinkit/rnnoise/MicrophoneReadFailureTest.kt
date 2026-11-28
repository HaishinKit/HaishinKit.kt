package com.haishinkit.rnnoise

import android.Manifest
import android.media.AudioRecord
import android.media.audiofx.AudioEffect
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioRecord
import java.io.IOException
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [MicrophoneReadFailureTest.NoEffects::class])
class MicrophoneReadFailureTest {
    @Test fun deadRecorderWithoutOsSuppressionFailsInsteadOfPollingSilenceAndCanReopen() =
        runBlocking<Unit> {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val owner = MediaMixer(context)
            val raw = AudioRecordSource(context).apply { sampleRate = 48000 }
            val input = RnnoiseMicrophoneSource(raw)
            var result = AudioRecord.ERROR_DEAD_OBJECT
            ShadowAudioRecord.setSource(
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInDirectBuffer(
                        buffer: ByteBuffer,
                        sizeInBytes: Int,
                        isBlocking: Boolean,
                    ) = result
                },
            )
            try {
                owner.attachAudio(0, input).getOrThrow()
                assertEquals(RnnoiseMicrophoneSource.OsNoiseSuppression.UNAVAILABLE, input.osNoiseSuppression)
                assertThrows(IOException::class.java) { input.read(0) }
                assertNotNull(input.lastFailure)
                owner.attachAudio(0, null).getOrThrow()
                assertNull(raw.audioSessionId)
                result = 128
                owner.attachAudio(0, input).getOrThrow()
                assertNull(input.lastFailure)
                assertEquals(128, input.read(0).payload!!.remaining())
                result = 0
                assertEquals(0, input.read(0).payload!!.remaining())
                assertNull(input.lastFailure)
            } finally {
                owner.attachAudio(0, null)
                owner.dispose()
                ShadowAudioRecord.clearSource()
            }
        }

    @Test fun legacyReadStillReturnsEmptyForNegativeCodesWhileCheckedReadThrows() =
        runBlocking<Unit> {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val raw = AudioRecordSource(context)
            ShadowAudioRecord.setSource(
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInDirectBuffer(
                        buffer: ByteBuffer,
                        sizeInBytes: Int,
                        isBlocking: Boolean,
                    ) = AudioRecord.ERROR_DEAD_OBJECT
                },
            )
            try {
                requireNotNull(raw.audioRecord).startRecording()
                assertTrue(raw.read(0).payload!!.remaining() == 0)
                assertThrows(IOException::class.java) { raw.readChecked(0) }
            } finally {
                raw.close()
                ShadowAudioRecord.clearSource()
            }
        }

    @Implements(AudioEffect::class)
    class NoEffects {
        companion object {
            @JvmStatic @Implementation
            fun queryEffects(): Array<AudioEffect.Descriptor> = emptyArray()
        }
    }
}
