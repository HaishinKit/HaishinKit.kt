package com.haishinkit.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AudioCodecBufferTest {
    @Test
    fun aacFrameTimestampsAdvanceInMicrosecondsWithoutAccumulatingRoundingError() {
        for (channels in 1..2) {
            val buffer =
                AudioCodecBuffer().apply {
                    sampleRate = 48000
                    channelCount = channels
                }
            val pcm = ByteBuffer.allocate(1024 * 2 * channels)
            val input = ByteBuffer.allocate(pcm.capacity())
            var firstTime = 0L
            repeat(3001) { index ->
                buffer.append(pcm)
                input.clear()
                assertEquals(pcm.capacity(), buffer.render(input))
                if (index == 0) firstTime = buffer.presentationTimestamp
                assertEquals(index * 1024L * 1_000_000 / 48000, buffer.presentationTimestamp - firstTime)
            }
        }
    }

    @Test
    fun clearResetsSampleClockAndDiscardsPendingAndQueuedPcm() {
        val buffer = AudioCodecBuffer().apply { sampleRate = 48000 }
        buffer.append(ByteBuffer.allocate(4096))
        buffer.render(ByteBuffer.allocate(2048))
        buffer.append(ByteBuffer.allocate(2048))
        buffer.clear()
        assertEquals(AudioCodecBuffer.DEFAULT_PRESENTATION_TIMESTAMP, buffer.presentationTimestamp)
        assertNull(buffer.render(ByteBuffer.allocate(2048)))

        buffer.append(ByteBuffer.allocate(2048))
        val beforeRender = System.nanoTime() / 1000
        buffer.render(ByteBuffer.allocate(2048))
        val firstTime = buffer.presentationTimestamp
        assertTrue(firstTime in beforeRender..System.nanoTime() / 1000)
        buffer.append(ByteBuffer.allocate(2048))
        buffer.render(ByteBuffer.allocate(2048))
        assertEquals(21_333L, buffer.presentationTimestamp - firstTime)
    }

    @Test
    fun pcmLengthAndStereoSampleClockUseValidBytesRatherThanCapacity() {
        val buffer =
            AudioCodecBuffer().apply {
                sampleRate = 48000
                channelCount = 2
            }
        val source = ByteBuffer.allocate(1024).apply { limit(192) }
        buffer.append(source)
        assertEquals(0, source.position())
        assertEquals(192, buffer.render(ByteBuffer.allocate(1024)))
        val firstTime = buffer.presentationTimestamp
        buffer.append(ByteBuffer.allocate(384))
        assertEquals(384, buffer.render(ByteBuffer.allocate(1024)))
        assertEquals(1000L, buffer.presentationTimestamp - firstTime)
    }

    @Test
    fun smallCodecInputsConsumePcmWithoutDroppingTheRemainingSamples() {
        val buffer = AudioCodecBuffer().apply { sampleRate = 44100 }
        val pcm = ByteArray(400) { it.toByte() }
        buffer.append(ByteBuffer.wrap(pcm))
        val result = mutableListOf<Byte>()
        var firstTime = 0L
        repeat(4) { index ->
            val input = ByteBuffer.allocate(100)
            assertEquals(100, buffer.render(input))
            if (index == 0) firstTime = buffer.presentationTimestamp
            assertEquals(index * 50L * 1_000_000 / 44100, buffer.presentationTimestamp - firstTime)
            result.addAll(input.array().toList())
        }
        assertArrayEquals(pcm, result.toByteArray())
        assertNull(buffer.render(ByteBuffer.allocate(100)))
    }

    @Test(timeout = 2000)
    fun encoderInputWithoutPcmReturnsWithoutBlockingCallbackThread() {
        assertNull(AudioCodecBuffer().render(ByteBuffer.allocate(1024)))
    }
}
