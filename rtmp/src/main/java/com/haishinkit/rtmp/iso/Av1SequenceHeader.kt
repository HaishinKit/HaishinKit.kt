package com.haishinkit.rtmp.iso

import java.nio.ByteBuffer

/** Fields needed for av1C and HLG validation, from AV1 sequence_header_obu(). */
internal data class Av1SequenceHeader(
    val profile: Int,
    val level: Int,
    val tier: Int,
    val bitDepth: Int,
    val monochrome: Int,
    val subsamplingX: Int,
    val subsamplingY: Int,
    val chromaPosition: Int,
    val colorPrimaries: Int,
    val transfer: Int,
    val matrix: Int,
    val fullRange: Boolean,
) {
    companion object {
        fun parse(payload: ByteBuffer): Av1SequenceHeader {
            val bits = Bits(payload)
            val profile = bits.read(3)
            bits.read(1)
            val reduced = bits.read(1) != 0
            var level = 0
            var tier = 0
            if (reduced) {
                level = bits.read(5)
            } else {
                var model = false
                var delayBits = 0
                if (bits.read(1) != 0) {
                    bits.read(32)
                    bits.read(32)
                    if (bits.read(1) != 0) bits.uvlc()
                    model = bits.read(1) != 0
                    if (model) {
                        delayBits = bits.read(5) + 1
                        bits.read(32)
                        bits.read(5)
                        bits.read(5)
                    }
                }
                val initialDelay = bits.read(1) != 0
                repeat(bits.read(5) + 1) { index ->
                    bits.read(12)
                    val operatingLevel = bits.read(5)
                    val operatingTier = if (operatingLevel > 7) bits.read(1) else 0
                    if (index == 0) {
                        level = operatingLevel
                        tier = operatingTier
                    }
                    if (model && bits.read(1) != 0) {
                        bits.read(delayBits)
                        bits.read(delayBits)
                        bits.read(1)
                    }
                    if (initialDelay && bits.read(1) != 0) bits.read(4)
                }
            }
            val widthBits = bits.read(4) + 1
            val heightBits = bits.read(4) + 1
            bits.read(widthBits)
            bits.read(heightBits)
            if (!reduced && bits.read(1) != 0) {
                bits.read(4)
                bits.read(3)
            }
            bits.read(1)
            bits.read(1)
            bits.read(1)
            if (!reduced) {
                bits.read(1)
                bits.read(1)
                bits.read(1)
                bits.read(1)
                val orderHint = bits.read(1) != 0
                if (orderHint) {
                    bits.read(1)
                    bits.read(1)
                }
                val screenContent = if (bits.read(1) != 0) 2 else bits.read(1)
                if (screenContent > 0 && bits.read(1) == 0) bits.read(1)
                if (orderHint) bits.read(3)
            }
            bits.read(1)
            bits.read(1)
            bits.read(1)
            val high = bits.read(1) != 0
            val depth =
                if (profile == 2 && high) {
                    if (bits.read(1) != 0) 12 else 10
                } else if (high) {
                    10
                } else {
                    8
                }
            val monochrome = if (profile == 1) 0 else bits.read(1)
            var primaries = 2
            var transfer = 2
            var matrix = 2
            if (bits.read(1) != 0) {
                primaries = bits.read(8)
                transfer = bits.read(8)
                matrix = bits.read(8)
            }
            var x = 1
            var y = 1
            var position = 0
            var fullRange = false
            if (monochrome != 0) {
                fullRange = bits.read(1) != 0
            } else {
                if (primaries == 1 && transfer == 13 && matrix == 0) {
                    fullRange = true
                    x = 0
                    y = 0
                } else {
                    fullRange = bits.read(1) != 0
                    when (profile) {
                        0 -> {
                            x = 1
                            y = 1
                        }
                        1 -> {
                            x = 0
                            y = 0
                        }
                        else ->
                            if (depth == 12) {
                                x = bits.read(1)
                                y = if (x != 0) bits.read(1) else 0
                            } else {
                                x = 1
                                y = 0
                            }
                    }
                    if (x != 0 && y != 0) position = bits.read(2)
                }
                bits.read(1)
            }
            return Av1SequenceHeader(profile, level, tier, depth, monochrome, x, y, position, primaries, transfer, matrix, fullRange)
        }
    }

    private class Bits(private val buffer: ByteBuffer) {
        private var value = 0
        private var remaining = 0

        fun read(count: Int): Int {
            var result = 0
            repeat(count) {
                if (remaining == 0) {
                    require(buffer.hasRemaining()) { "Truncated AV1 sequence header" }
                    value = buffer.get().toInt() and 255
                    remaining = 8
                }
                result = (result shl 1) or (value shr --remaining and 1)
            }
            return result
        }

        fun uvlc() {
            var leading = 0
            while (read(1) == 0) {
                if (++leading == 32) return
            }
            read(leading)
        }
    }
}
