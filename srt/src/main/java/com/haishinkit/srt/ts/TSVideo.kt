package com.haishinkit.srt.ts

import java.io.ByteArrayOutputStream

/** Annex-B/length-prefixed NAL conversion and SPS dimensions, independent of Android. */
internal object TSVideo {
    /** The caller selects framing; the two formats have ambiguous byte patterns. */
    enum class Framing { ANNEX_B, LENGTH_PREFIXED }

    data class Slice(val data: ByteArray, val offset: Int, val size: Int) {
        fun bytes(): ByteArray = data.copyOfRange(offset, offset + size)
    }

    /** Views into the caller-owned access unit; VCL payloads are not copied. */
    fun slices(
        data: ByteArray,
        framing: Framing = Framing.ANNEX_B,
    ): List<Slice> {
        if (framing == Framing.LENGTH_PREFIXED) return lengthPrefixedSlices(data)
        val result = mutableListOf<Slice>()
        var start = -1
        var position = 0
        while (position + 2 < data.size) {
            val length = startCode(data, position)
            if (length != 0) {
                if (start >= 0 && position > start) result.add(Slice(data, start, position - start))
                require(result.size < 4096) { "Too many NAL units" }
                start = position + length
                position = start
            } else {
                position++
            }
        }
        if (start >= 0 && start < data.size) result.add(Slice(data, start, data.size - start))
        return if (result.isNotEmpty()) {
            result
        } else if (data.isEmpty()) {
            emptyList()
        } else {
            listOf(Slice(data, 0, data.size))
        }
    }

    fun units(
        data: ByteArray,
        framing: Framing = Framing.ANNEX_B,
    ): List<ByteArray> = slices(data, framing).map { it.bytes() }

    fun type(
        unit: Slice,
        codec: TSCodec,
    ): Int =
        if (unit.size == 0) {
            -1
        } else if (codec == TSCodec.AVC) {
            unit.data[unit.offset].toInt() and 31
        } else {
            (unit.data[unit.offset].toInt() shr 1) and 63
        }

    fun hasKey(
        data: ByteArray,
        codec: TSCodec,
        framing: Framing = Framing.ANNEX_B,
    ): Boolean = slices(data, framing).any { if (codec == TSCodec.AVC) type(it, codec) == 5 else type(it, codec) in 16..21 }

    fun configuration(
        data: ByteArray,
        codec: TSCodec,
        framing: Framing = Framing.ANNEX_B,
    ): List<ByteArray> = slices(data, framing).filter { type(it, codec) in if (codec == TSCodec.AVC) 7..8 else 32..34 }.map { it.bytes() }

    fun annexB(units: List<ByteArray>): ByteArray {
        val result = ByteArray(units.sumOf { it.size + 4 })
        var offset = 0
        units.forEach { unit ->
            result[offset + 3] = 1
            unit.copyInto(result, offset + 4)
            offset += 4 + unit.size
        }
        return result
    }

    fun type(
        unit: ByteArray,
        codec: TSCodec,
    ): Int =
        if (unit.isEmpty()) {
            -1
        } else if (codec == TSCodec.AVC) {
            unit[0].toInt() and 31
        } else {
            (unit[0].toInt() shr 1) and 63
        }

    fun isKey(
        unit: ByteArray,
        codec: TSCodec,
    ): Boolean = if (codec == TSCodec.AVC) type(unit, codec) == 5 else type(unit, codec) in 16..21

    fun configuration(
        units: List<ByteArray>,
        codec: TSCodec,
    ): List<ByteArray> = units.filter { type(it, codec) in if (codec == TSCodec.AVC) 7..8 else 32..34 }

    fun hasConfiguration(
        units: List<ByteArray>,
        codec: TSCodec,
    ): Boolean = (if (codec == TSCodec.AVC) setOf(7, 8) else setOf(32, 33, 34)).all { required -> units.any { type(it, codec) == required } }

    fun dimensions(
        sps: ByteArray,
        codec: TSCodec,
    ): Pair<Int, Int> {
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
        val bits = Bits(rbsp.toByteArray())
        val size = if (codec == TSCodec.AVC) avcDimensions(bits) else hevcDimensions(bits)
        require(size.first in 1..16384 && size.second in 1..16384) { "Invalid SPS dimensions" }
        return size
    }

    private fun lengthPrefixedSlices(data: ByteArray): List<Slice> {
        val result = mutableListOf<Slice>()
        var offset = 0
        while (offset < data.size) {
            require(data.size - offset >= 4) { "Truncated NAL length" }
            val size =
                (TSBinary.u8(data, offset).toLong() shl 24) or (TSBinary.u8(data, offset + 1).toLong() shl 16) or
                    (TSBinary.u8(data, offset + 2).toLong() shl 8) or TSBinary.u8(data, offset + 3).toLong()
            require(size > 0 && size <= data.size - offset - 4) { "Invalid NAL length" }
            require(result.size < 4096) { "Too many NAL units" }
            result.add(Slice(data, offset + 4, size.toInt()))
            offset += 4 + size.toInt()
        }
        return result
    }

    private fun startCode(
        data: ByteArray,
        offset: Int,
    ): Int {
        if (offset + 2 >= data.size || data[offset] != 0.toByte() || data[offset + 1] != 0.toByte()) return 0
        if (data[offset + 2] == 1.toByte()) return 3
        return if (offset + 3 < data.size && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) 4 else 0
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

    private fun hevcDimensions(bits: Bits): Pair<Int, Int> {
        bits.skip(16 + 4)
        val subLayers = bits.read(3)
        bits.skip(1 + 96)
        val profile = BooleanArray(subLayers)
        val level = BooleanArray(subLayers)
        repeat(subLayers) {
            profile[it] = bits.read(1) != 0
            level[it] = bits.read(1) != 0
        }
        if (subLayers > 0) bits.skip((8 - subLayers) * 2)
        repeat(subLayers) {
            if (profile[it]) bits.skip(88)
            if (level[it]) bits.skip(8)
        }
        bits.ue()
        val chroma = bits.ue()
        require(chroma in 0..3)
        val separate = chroma == 3 && bits.read(1) != 0
        val width = bits.ue()
        val height = bits.ue()
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
        return width - (left + right) * (if (format == 1 || format == 2) 2 else 1) to height - (top + bottom) * (if (format == 1) 2 else 1)
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
