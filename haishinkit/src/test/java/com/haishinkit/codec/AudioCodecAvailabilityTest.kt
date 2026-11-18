package com.haishinkit.codec

import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AudioCodecAvailabilityTest {
    @Test
    fun missingOpusEncoderFailsBeforeStarting() {
        ShadowMediaCodecList.reset()
        val codec = AudioCodec().apply { outputMimeType = MediaFormat.MIMETYPE_AUDIO_OPUS }

        val error = assertThrows(IllegalStateException::class.java) { codec.startRunning() }

        assertTrue(error.message.orEmpty().contains("Opus encoder is unavailable"))
        assertFalse(codec.isRunning.get())
        codec.dispose()
    }
}
