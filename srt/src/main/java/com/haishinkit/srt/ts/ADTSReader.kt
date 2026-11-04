package com.haishinkit.srt.ts

/** Bounded per-track ADTS assembly; headers and frames may cross PES boundaries. */
internal class ADTSReader {
    private var pending = byteArrayOf()
    private var anchorUs: Long? = null
    private var samples = 0L
    private var rate = 0

    fun read(
        payload: ByteArray,
        timestampUs: Long?,
    ): List<TSSample> {
        val carrySize = pending.size
        val bytes = if (carrySize == 0) payload else pending + payload
        pending = byteArrayOf()
        if (carrySize == 0 && timestampUs != null) resetTime(timestampUs)
        if (anchorUs == null) return emptyList()
        var anchorUsed = carrySize == 0
        var offset = 0
        val output = mutableListOf<TSSample>()
        while (offset < bytes.size) {
            if (!anchorUsed && offset >= carrySize) {
                if (timestampUs != null) resetTime(timestampUs)
                anchorUsed = true
            }
            if (TSBinary.u8(bytes, offset) != 255 || (offset + 1 < bytes.size && TSBinary.u8(bytes, offset + 1) and 0xf6 != 0xf0)) {
                offset++
                continue
            }
            if (bytes.size - offset < 7) {
                retain(bytes, offset)
                break
            }
            val header = if (TSBinary.u8(bytes, offset + 1) and 1 != 0) 7 else 9
            val frequency = (TSBinary.u8(bytes, offset + 2) shr 2) and 15
            val channels = ((TSBinary.u8(bytes, offset + 2) and 1) shl 2) or (TSBinary.u8(bytes, offset + 3) shr 6)
            val size = ((TSBinary.u8(bytes, offset + 3) and 3) shl 11) or (TSBinary.u8(bytes, offset + 4) shl 3) or (TSBinary.u8(bytes, offset + 5) shr 5)
            if (frequency >= TSAudioConfig.RATES.size || channels !in 1..7 || size < header || TSBinary.u8(bytes, offset + 6) and 3 != 0) {
                offset++
                continue
            }
            if (offset + size > bytes.size) {
                retain(bytes, offset)
                break
            }
            val config = TSAudioConfig(TSAudioConfig.RATES[frequency], channels, (TSBinary.u8(bytes, offset + 2) shr 6) + 1)
            if (rate != config.sampleRate) {
                resetTime(timeUs())
                rate = config.sampleRate
            }
            output.add(TSSample(TSCodec.AAC, bytes.copyOfRange(offset + header, offset + size), timeUs(), audio = config, keyframe = true))
            samples += 1024
            offset += size
        }
        return output
    }

    private fun timeUs(): Long = requireNotNull(anchorUs) + if (rate == 0) 0 else samples * 1_000_000 / rate

    private fun resetTime(timeUs: Long) {
        anchorUs = timeUs
        samples = 0
        rate = 0
    }

    private fun retain(
        bytes: ByteArray,
        offset: Int,
    ) {
        check(bytes.size - offset <= 8191)
        pending = bytes.copyOfRange(offset, bytes.size)
    }
}
