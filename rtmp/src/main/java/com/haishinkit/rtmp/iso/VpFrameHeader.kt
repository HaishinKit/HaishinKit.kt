package com.haishinkit.rtmp.iso

import android.media.MediaFormat
import android.util.Size
import java.nio.ByteBuffer

/** Parses uncompressed VP keyframe metadata, not the coded image. */
internal data class VpFrameHeader(
    val size: Size,
    val profile: Int = 0,
    val bitDepth: Int = 8,
    val chroma: Int = 1,
    val fullRange: Int = 0,
    val primaries: Int = 2,
    val transfer: Int = 2,
    val matrix: Int = 2,
) {
    companion object {
        fun read(
            mime: String,
            source: ByteBuffer,
        ): VpFrameHeader {
            val buffer = source.duplicate()
            if (mime == MediaFormat.MIMETYPE_VIDEO_VP8) {
                require(buffer.remaining() >= 10) { "Truncated VP8 keyframe" }
                require(buffer.get().toInt() and 1 == 0) { "VP8 frame is not a keyframe" }
                buffer.get()
                buffer.get()
                require(buffer.get().toInt() and 0xFF == 0x9D && buffer.get().toInt() and 0xFF == 0x01 && buffer.get().toInt() and 0xFF == 0x2A) { "Invalid VP8 sync code" }
                val width = (buffer.get().toInt() and 0xFF) or ((buffer.get().toInt() and 0x3F) shl 8)
                val height = (buffer.get().toInt() and 0xFF) or ((buffer.get().toInt() and 0x3F) shl 8)
                require(width > 0 && height > 0) { "Invalid VP8 dimensions" }
                return VpFrameHeader(Size(width, height))
            }
            require(mime == MediaFormat.MIMETYPE_VIDEO_VP9)
            val bits = Bits(buffer)
            require(bits.read(2) == 2) { "Invalid VP9 frame marker" }
            val profile = bits.read(1) or (bits.read(1) shl 1)
            if (profile == 3) require(bits.read(1) == 0) { "Invalid VP9 reserved bit" }
            require(bits.read(1) == 0 && bits.read(1) == 0) { "VP9 frame is not a keyframe" }
            bits.read(1) // show_frame
            bits.read(1) // error_resilient_mode
            require(bits.read(24) == 0x498342) { "Invalid VP9 sync code" }
            val depth = if (profile >= 2) (if (bits.read(1) == 1) 12 else 10) else 8
            val color = bits.read(3)
            require(color != 6) { "Reserved VP9 color space" }
            val fullRange: Int
            var x = 1
            var y = 1
            if (color != 7) {
                fullRange = bits.read(1)
                if (profile == 1 || profile == 3) {
                    x = bits.read(1)
                    y = bits.read(1)
                    require(bits.read(1) == 0) { "Invalid VP9 color reserved bit" }
                }
            } else {
                require(profile == 1 || profile == 3) { "VP9 RGB requires profile 1 or 3" }
                fullRange = 1
                x = 0
                y = 0
                require(bits.read(1) == 0) { "Invalid VP9 RGB reserved bit" }
            }
            val chroma =
                when {
                    x == 1 && y == 1 -> 1
                    x == 1 && y == 0 -> 2
                    x == 0 && y == 0 -> 3
                    else -> throw IllegalArgumentException("VP configuration cannot signal 4:4:0")
                }
            val size = Size(bits.read(16) + 1, bits.read(16) + 1)
            val colors =
                when (color) {
                    1, 3 -> intArrayOf(6, 6, 6)
                    2 -> intArrayOf(1, 1, 1)
                    4 -> intArrayOf(7, 7, 7)
                    5 -> intArrayOf(9, 2, 9) // Transfer is not specified by the VP9 header.
                    7 -> intArrayOf(1, 13, 0)
                    else -> intArrayOf(2, 2, 2)
                }
            return VpFrameHeader(size, profile, depth, chroma, fullRange, colors[0], colors[1], colors[2])
        }
    }

    private class Bits(private val buffer: ByteBuffer) {
        private var byte = 0
        private var remaining = 0

        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                if (remaining == 0) {
                    require(buffer.hasRemaining()) { "Truncated VP9 keyframe header" }
                    byte = buffer.get().toInt() and 0xFF
                    remaining = 8
                }
                value = (value shl 1) or ((byte ushr --remaining) and 1)
            }
            return value
        }
    }
}
