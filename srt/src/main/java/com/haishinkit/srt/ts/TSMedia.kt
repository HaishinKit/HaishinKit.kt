package com.haishinkit.srt.ts

internal enum class TSCodec(val streamType: Int, val mime: String) {
    AVC(0x1b, "video/avc"),
    HEVC(0x24, "video/hevc"),
    AAC(0x0f, "audio/mp4a-latm"),
}

internal data class TSAudioConfig(val sampleRate: Int, val channels: Int, val objectType: Int = 2) {
    val frequencyIndex: Int = RATES.indexOf(sampleRate)

    init {
        require(frequencyIndex >= 0 && channels in 1..7 && objectType in 1..4) { "Unsupported ADTS audio configuration" }
    }

    fun csd(): ByteArray = byteArrayOf(((objectType shl 3) or (frequencyIndex shr 1)).toByte(), (((frequencyIndex and 1) shl 7) or (channels shl 3)).toByte())

    fun adts(size: Int): ByteArray {
        val length = size + 7
        require(length <= 8191) { "AAC frame exceeds ADTS length limit" }
        return byteArrayOf(
            0xff.toByte(),
            0xf1.toByte(),
            (((objectType - 1) shl 6) or (frequencyIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            (length shr 3).toByte(),
            (((length and 7) shl 5) or 0x1f).toByte(),
            0xfc.toByte(),
        )
    }

    companion object {
        val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

internal data class TSSample(
    val codec: TSCodec,
    val data: ByteArray,
    val presentationTimeUs: Long,
    val decodeTimeUs: Long = presentationTimeUs,
    val keyframe: Boolean = false,
    val audio: TSAudioConfig? = null,
)

internal object TSBinary {
    const val CLOCK = 90_000L
    const val WRAP = 1L shl 33
    const val MASK = WRAP - 1

    fun ticks(us: Long): Long = us / 1_000_000 * CLOCK + us % 1_000_000 * CLOCK / 1_000_000

    fun micros(ticks: Long): Long = ticks / CLOCK * 1_000_000 + ticks % CLOCK * 1_000_000 / CLOCK

    fun u8(
        bytes: ByteArray,
        index: Int,
    ): Int = bytes[index].toInt() and 255

    fun u16(
        bytes: ByteArray,
        index: Int,
    ): Int = (u8(bytes, index) shl 8) or u8(bytes, index + 1)

    fun crc(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Int {
        var value = -1
        for (i in offset until offset + length) {
            value = value xor (u8(bytes, i) shl 24)
            repeat(8) { value = (value shl 1) xor if (value < 0) 0x04c11db7 else 0 }
        }
        return value
    }

    fun timestamp(
        value: Long,
        prefix: Int,
    ): ByteArray {
        val v = value and MASK
        return byteArrayOf(
            (prefix or (((v shr 30).toInt() and 7) shl 1) or 1).toByte(),
            (v shr 22).toByte(),
            ((((v shr 15).toInt() and 127) shl 1) or 1).toByte(),
            (v shr 7).toByte(),
            (((v.toInt() and 127) shl 1) or 1).toByte(),
        )
    }

    fun timestamp(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        require((u8(bytes, offset) and 1) == 1 && (u8(bytes, offset + 2) and 1) == 1 && (u8(bytes, offset + 4) and 1) == 1) { "Invalid PES timestamp markers" }
        return ((u8(bytes, offset).toLong() and 14) shl 29) or (u8(bytes, offset + 1).toLong() shl 22) or
            ((u8(bytes, offset + 2).toLong() and 254) shl 14) or (u8(bytes, offset + 3).toLong() shl 7) or (u8(bytes, offset + 4).toLong() shr 1)
    }
}
