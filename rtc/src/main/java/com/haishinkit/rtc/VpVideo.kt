package com.haishinkit.rtc

import java.util.concurrent.ConcurrentSkipListMap

/** Uncompressed keyframe metadata only; decoding stays with Android MediaCodec. */
internal object VpVideo {
    /** VP9 reference/display pictures may share RTP time; MediaCodec input PTS must be unique. */
    fun decoderTime(
        codec: RtcVideoCodec,
        timestamp: Long,
        previousRtp: Long,
        previousInput: Long,
    ): Long? {
        if (codec != RtcVideoCodec.VP9 && timestamp <= previousRtp) return null
        return maxOf(timestamp, previousInput + 1)
    }

    fun keyframe(
        codec: RtcVideoCodec,
        data: ByteArray,
    ): Boolean {
        require(data.isNotEmpty()) { "Empty VP frame" }
        val first = data[0].toInt() and 255
        return when (codec) {
            RtcVideoCodec.VP8 -> first and 1 == 0
            RtcVideoCodec.VP9 -> {
                require(first and 0xf0 == 0x80) { "RTC VP9 requires profile 0" }
                first and 0x0c == 0
            }
            else -> error("Not a VP codec")
        }
    }

    fun dimensions(
        codec: RtcVideoCodec,
        data: ByteArray,
    ): Pair<Int, Int> {
        require(keyframe(codec, data)) { "Expected a VP keyframe" }
        val size =
            if (codec == RtcVideoCodec.VP8) {
                require(data.size >= 10 && data[3] == 0x9d.toByte() && data[4] == 1.toByte() && data[5] == 0x2a.toByte()) { "Invalid VP8 keyframe" }
                ((data[6].toInt() and 255) or ((data[7].toInt() and 63) shl 8)) to
                    ((data[8].toInt() and 255) or ((data[9].toInt() and 63) shl 8))
            } else {
                val bits = Bits(data)
                bits.read(8)
                require(bits.read(24) == 0x498342) { "Invalid VP9 sync code" }
                val color = bits.read(3)
                require(color != 6 && color != 7) { "Invalid VP9 profile 0 color space" }
                bits.read(1) // color_range
                (bits.read(16) + 1) to (bits.read(16) + 1)
            }
        require(size.first > 0 && size.second > 0) { "Invalid VP dimensions" }
        return size
    }

    /** Decoder PTS are unique/increasing; output keeps the original RTP presentation time.
     * Hidden pictures can refer to a later sampling time than the following visible picture.
     * Unused hidden-picture entries are bounded above the input/output queue capacities.
     */
    class Timestamps {
        private val values = ConcurrentSkipListMap<Long, Long>()

        fun remember(
            inputTime: Long,
            rtpTime: Long,
        ) {
            values[inputTime] = rtpTime
            while (values.size > 128) values.pollFirstEntry()
        }

        fun outputTime(inputTime: Long): Long = values.remove(inputTime) ?: inputTime
    }

    private class Bits(private val data: ByteArray) {
        private var offset = 0

        fun read(count: Int): Int {
            require(offset + count <= data.size * 8) { "Truncated VP9 keyframe" }
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((data[offset / 8].toInt() ushr (7 - offset % 8)) and 1)
                offset++
            }
            return value
        }
    }
}
