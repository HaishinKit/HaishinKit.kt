package com.haishinkit.rtc

import java.io.ByteArrayOutputStream

/** H.264 access-unit framing and bounded SPS parsing. */
internal object H264 {
    fun units(data: ByteArray): List<ByteArray> {
        require(data.size <= 4 * 1024 * 1024) { "H.264 access unit is too large" }
        val result = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < data.size) {
            val length =
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) {
                        3
                    } else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        4
                    } else {
                        0
                    }
                } else {
                    0
                }
            if (length > 0) {
                if (start >= 0 && i > start) result.add(data.copyOfRange(start, i))
                require(result.size < 4096)
                start = i + length
                i = start
            } else {
                i++
            }
        }
        if (start >= 0 && start < data.size) result.add(data.copyOfRange(start, data.size))
        require(start >= 0 || data.isEmpty()) { "Expected Annex-B H.264" }
        return result
    }

    fun annexB(units: List<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        units.forEach {
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(it)
        }
        return output.toByteArray()
    }

    fun dimensions(sps: ByteArray): Pair<Int, Int> {
        require(sps.size in 4..65536 && sps[0].toInt() and 31 == 7) { "Invalid H.264 SPS" }
        val rbsp = ByteArrayOutputStream()
        var zeros = 0
        sps.forEach { byte ->
            if (zeros >= 2 && byte == 3.toByte()) {
                zeros = 0
            } else {
                rbsp.write(byte.toInt())
                zeros = if (byte == 0.toByte()) zeros + 1 else 0
            }
        }
        return avcDimensions(Bits(rbsp.toByteArray())).also {
            require(it.first in 1..8192 && it.second in 1..8192) { "Unsupported video dimensions" }
        }
    }

    private fun avcDimensions(bits: Bits): Pair<Int, Int> {
        bits.skip(8)
        val profile = bits.read(8)
        bits.skip(16)
        bits.ue()
        var chroma = 1
        var separate = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue()
            require(chroma in 0..3)
            if (chroma == 3) separate = bits.read(1) != 0
            bits.ue()
            bits.ue()
            bits.skip(1)
            if (bits.read(1) != 0) {
                repeat(if (chroma == 3) 12 else 8) { index ->
                    if (bits.read(1) != 0) {
                        var last = 8
                        var next = 8
                        repeat(if (index < 6) 16 else 64) {
                            if (next != 0) next = (last + bits.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        bits.ue()
        when (bits.ue()) {
            0 -> bits.ue()
            1 -> {
                bits.skip(1)
                bits.se()
                bits.se()
                val count = bits.ue()
                require(count <= 256)
                repeat(count) { bits.se() }
            }
        }
        bits.ue()
        bits.skip(1)
        val width = (bits.ue() + 1) * 16
        val mapHeight = bits.ue() + 1
        val frameOnly = bits.read(1)
        if (frameOnly == 0) bits.skip(1)
        bits.skip(1)
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        if (bits.read(1) != 0) {
            left = bits.ue()
            right = bits.ue()
            top = bits.ue()
            bottom = bits.ue()
        }
        val format = if (separate) 0 else chroma
        val cropX = if (format == 1 || format == 2) 2 else 1
        val cropY = (if (format == 1) 2 else 1) * (2 - frameOnly)
        return width - (left + right) * cropX to mapHeight * 16 * (2 - frameOnly) - (top + bottom) * cropY
    }

    private class Bits(val data: ByteArray) {
        var position = 0

        fun skip(count: Int) {
            require(count >= 0 && position + count <= data.size * 8)
            position += count
        }

        fun read(count: Int): Int {
            require(count in 0..31 && position + count <= data.size * 8)
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((data[position / 8].toInt() shr (7 - position % 8)) and 1)
                position++
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) {
                zeros++
                require(zeros <= 30)
            }
            return (1 shl zeros) - 1 + read(zeros)
        }

        fun se(): Int {
            val value = ue()
            return if (value and 1 == 0) -(value / 2) else (value + 1) / 2
        }
    }
}
