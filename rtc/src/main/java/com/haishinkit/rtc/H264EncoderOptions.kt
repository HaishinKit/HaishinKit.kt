package com.haishinkit.rtc

import android.media.MediaFormat
import com.haishinkit.codec.CodecOption

/** Format overrides must not bypass the settings validated for the SDP offer. */
internal object H264EncoderOptions {
    private val fixedFields =
        setOf(
            MediaFormat.KEY_PROFILE,
            MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_WIDTH,
            MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_FRAME_RATE,
            MediaFormat.KEY_CAPTURE_RATE,
            MediaFormat.KEY_BIT_RATE,
            "max-bitrate",
        )

    fun validate(options: List<CodecOption>) {
        require(options.none { it.key in fixedFields }) {
            "RTC codec options cannot override profile, level, dimensions, frame rate or bitrate; configure videoSetting fields before connecting"
        }
    }
}
