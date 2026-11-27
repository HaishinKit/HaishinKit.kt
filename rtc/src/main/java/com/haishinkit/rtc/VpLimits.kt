package com.haishinkit.rtc

import android.media.MediaCodec
import kotlin.math.sqrt

/** Conservative SDP receive envelope, not a performance benchmark or the device maximum. */
internal data class VpLimits(val maxFs: Int, val maxFr: Int) {
    init {
        require(maxFs > 0 && maxFr > 0)
    }

    companion object {
        fun forDecoder(codec: RtcVideoCodec): VpLimits {
            val decoder = MediaCodec.createDecoderByType(codec.mime)
            try {
                val caps = checkNotNull(decoder.codecInfo.getCapabilitiesForType(codec.mime).videoCapabilities)
                return select(caps.supportedWidths.lower, caps.supportedWidths.upper, caps.supportedHeights.lower, caps.supportedHeights.upper) { width, height, rate ->
                    // Account for the selected decoder's buffer alignment as well as
                    // size-dependent frame rate limits, rather than multiplying maxima.
                    val alignedWidth = (width + caps.widthAlignment - 1) / caps.widthAlignment * caps.widthAlignment
                    val alignedHeight = (height + caps.heightAlignment - 1) / caps.heightAlignment * caps.heightAlignment
                    caps.areSizeAndRateSupported(alignedWidth, alignedHeight, rate.toDouble())
                }
            } finally {
                decoder.release()
            }
        }

        fun select(
            minWidth: Int,
            maxWidth: Int,
            minHeight: Int,
            maxHeight: Int,
            supports: (Int, Int, Int) -> Boolean,
        ): VpLimits {
            val sideLimit = minOf(maxWidth, maxHeight) / 16
            val axisFs = (sideLimit.toLong() * sideLimit / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            for (rate in listOf(30, 15, 5)) {
                for (fs in listOf(8160, 3600, 1200, 360, 99).map { minOf(it, axisFs) }.distinct().filter { it > 0 }) {
                    // RFC 7741/9628: each dimension is strictly below floor(sqrt(8*fs)) MB.
                    val side = sqrt(fs * 8.0).toInt() - 1
                    var checked = false
                    val fits =
                        ((minWidth + 15) / 16).coerceAtLeast(1).rangeTo(side).all { columns ->
                            val height = minOf(side, fs / columns) * 16
                            if (height < minHeight) {
                                true
                            } else {
                                checked = true
                                supports(columns * 16, height, rate)
                            }
                        }
                    if (checked && fits) return VpLimits(fs, rate)
                }
            }
            error("The selected decoder has no supported VP receive envelope")
        }
    }
}
