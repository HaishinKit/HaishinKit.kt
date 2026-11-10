package com.haishinkit.rtc

import android.media.MediaFormat
import com.haishinkit.codec.CodecOption
import org.junit.Assert.assertThrows
import org.junit.Test

class H264EncoderOptionsTest {
    @Test fun formatOverridesCannotTurnValidatedSettingsInto1080p() {
        assertThrows(IllegalArgumentException::class.java) {
            H264EncoderOptions.validate(listOf(CodecOption(MediaFormat.KEY_WIDTH, 1920), CodecOption(MediaFormat.KEY_HEIGHT, 1080)))
        }
    }

    @Test fun rateAndProfileOverridesCannotBypassNegotiation() {
        assertThrows(IllegalArgumentException::class.java) { H264EncoderOptions.validate(listOf(CodecOption(MediaFormat.KEY_FRAME_RATE, 60))) }
        assertThrows(IllegalArgumentException::class.java) { H264EncoderOptions.validate(listOf(CodecOption(MediaFormat.KEY_BIT_RATE, 20_000_000))) }
        assertThrows(IllegalArgumentException::class.java) { H264EncoderOptions.validate(listOf(CodecOption(MediaFormat.KEY_PROFILE, 8))) }
    }

    @Test fun unrelatedEncoderOptionsRemainAvailable() {
        H264EncoderOptions.validate(listOf(CodecOption(MediaFormat.KEY_I_FRAME_INTERVAL, 1)))
    }
}
