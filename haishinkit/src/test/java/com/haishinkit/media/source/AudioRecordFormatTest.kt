package com.haishinkit.media.source

import android.Manifest
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 31])
class AudioRecordFormatTest {
    @After fun tearDown() = ShadowAudioRecord.clearSource()

    @Test fun partialAndFailedReadsNeverForwardStaleAudio() =
        runBlocking {
            val source = source()
            var count = 2048
            ShadowAudioRecord.setSourceProvider { _ ->
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInDirectBuffer(
                        buffer: ByteBuffer,
                        size: Int,
                        blocking: Boolean,
                    ): Int {
                        for (index in 0 until count.coerceAtLeast(0)) buffer.put(index, 0x55.toByte())
                        return count
                    }
                }
            }
            try {
                source.audioRecord!!.startRecording()
                assertEquals(2048, source.read(0).payload!!.remaining())
                count = 80
                assertEquals(80, source.read(0).payload!!.remaining())
                count = AudioRecord.ERROR_INVALID_OPERATION
                assertEquals(0, source.read(0).payload!!.remaining())
                count = 0
                assertEquals(0, source.read(0).payload!!.remaining())
            } finally {
                source.close()
            }
        }

    @Test fun mutingZerosOnlyTheValidReadAndUnmutingRestoresInput() =
        runBlocking {
            val source = source()
            ShadowAudioRecord.setSourceProvider { _ ->
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInDirectBuffer(
                        buffer: ByteBuffer,
                        size: Int,
                        blocking: Boolean,
                    ): Int {
                        for (index in 0 until 64) buffer.put(index, 0x55.toByte())
                        return 64
                    }
                }
            }
            try {
                source.audioRecord!!.startRecording()
                source.isMuted = true
                val muted = source.read(0).payload!!
                assertEquals(64, muted.remaining())
                while (muted.hasRemaining()) assertEquals(0, muted.get().toInt())
                source.isMuted = false
                assertEquals(0x55.toByte(), source.read(0).payload!!.get())
            } finally {
                source.close()
            }
        }

    @Test fun deadRecorderRecoversAtTheOriginalClientRate() =
        runBlocking {
            val source = source()
            var dead = true
            ShadowAudioRecord.setSourceProvider { _ ->
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInDirectBuffer(
                        buffer: ByteBuffer,
                        size: Int,
                        blocking: Boolean,
                    ): Int =
                        if (dead) AudioRecord.ERROR_DEAD_OBJECT.also { dead = false } else size
                }
            }
            try {
                val original = source.audioRecord!!
                original.startRecording()
                // Configuration changes are only for the next open, not a route recovery.
                source.sampleRate = 16_000
                assertEquals(0, source.read(0).payload!!.remaining())
                val replacement = source.audioRecord!!
                assertNotSame(original, replacement)
                assertEquals(AudioRecord.STATE_UNINITIALIZED, original.state)
                assertEquals(48_000, replacement.sampleRate)
                assertEquals(1, replacement.channelCount)
                assertEquals(AudioRecord.RECORDSTATE_RECORDING, replacement.recordingState)
                assertTrue(source.read(0).payload!!.hasRemaining())
                assertSame(replacement, source.audioRecord)
            } finally {
                source.close()
            }
        }

    @Test fun optInUsesVoiceCaptureWithoutChangingTheDefaultSource() =
        runBlocking {
            val source = source()
            try {
                assertEquals(MediaRecorder.AudioSource.CAMCORDER, source.audioRecord!!.audioSource)
                source.close()
                source.preferBluetoothMicrophone = true
                assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, source.audioRecord!!.audioSource)
                assertEquals(48_000, source.audioRecord!!.sampleRate)
            } finally {
                source.close()
            }
        }

    @Test fun closeDuringADeadReadDoesNotReopenTheRecorder() =
        runBlocking {
            val source = source()
            var reads = 0
            ShadowAudioRecord.setSourceProvider { _ ->
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInDirectBuffer(
                        buffer: ByteBuffer,
                        size: Int,
                        blocking: Boolean,
                    ): Int {
                        reads++
                        runBlocking { source.close() }
                        return AudioRecord.ERROR_DEAD_OBJECT
                    }
                }
            }
            val original = source.audioRecord!!
            original.startRecording()
            assertEquals(0, source.read(0).payload!!.remaining())
            assertEquals(AudioRecord.STATE_UNINITIALIZED, original.state)
            assertEquals(0, source.read(0).payload!!.remaining())
            assertEquals(1, reads)
        }

    private fun source(): AudioRecordSource {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        return AudioRecordSource(application).apply { sampleRate = 48_000 }
    }
}
