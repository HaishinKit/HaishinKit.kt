package com.haishinkit.srt.ts

/** One-program MPEG-TS writer. One owned wire buffer per access unit, no PES/packet payload copies. */
internal class TSWriter(codecs: Set<TSCodec>) {
    private val codecs = codecs.toSet()
    private val counters = IntArray(8192)
    private val lastCounters = IntArray(8192)
    private val seen = BooleanArray(8192)
    private val pcrPID = if (codecs.any { it != TSCodec.AAC }) VIDEO_PID else AUDIO_PID
    private val pat = section(0, byteArrayOf(0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xef.toByte(), 0xff.toByte()))
    private val pmt = makePMT()
    private var origin: Long? = null
    private var programTime: Long? = null
    private var pcrTime: Long? = null
    private var videoConfig = emptyList<ByteArray>()

    init {
        require(codecs.isNotEmpty() && codecs.count { it != TSCodec.AAC } <= 1) { "One video and one audio track are supported" }
    }

    fun setVideoConfig(data: List<ByteArray>) {
        videoConfig = data.map { it.copyOf() }
    }

    fun write(sample: TSSample): ByteArray {
        require(sample.codec in codecs) { "Sample track is absent from the PMT" }
        if (origin == null) origin = sample.decodeTimeUs
        val dts = TSBinary.ticks(sample.decodeTimeUs - origin!! + 100_000)
        val pts = TSBinary.ticks(sample.presentationTimeUs - origin!! + 100_000)
        val pid = if (sample.codec == TSCodec.AAC) AUDIO_PID else VIDEO_PID
        val program = programTime == null || sample.decodeTimeUs - programTime!! >= 100_000 || sample.keyframe
        val clock = pcrTime == null || sample.decodeTimeUs - pcrTime!! >= 20_000
        val clockOnly = clock && pid != pcrPID
        val pcr = if (clock && !clockOnly) (dts - 9000) and TSBinary.MASK else null
        val payload = payload(sample)
        val payloadSize = payload.sumOf { it.size }
        val both = sample.decodeTimeUs != sample.presentationTimeUs
        val times = TSBinary.timestamp(pts, if (both) 0x30 else 0x20) + if (both) TSBinary.timestamp(dts, 0x10) else byteArrayOf()
        val length = payloadSize + 3 + times.size
        require(sample.codec != TSCodec.AAC || length <= 65535)
        val pesLength = if (length > 65535) 0 else length
        val header =
            byteArrayOf(
                0, 0, 1, (if (sample.codec == TSCodec.AAC) 0xc0 else 0xe0).toByte(),
                (pesLength shr 8).toByte(), pesLength.toByte(), 0x80.toByte(), (if (both) 0xc0 else 0x80).toByte(), times.size.toByte(),
            ) + times
        val parts = listOf(slice(header)) + payload
        val random = sample.keyframe || sample.codec == TSCodec.AAC
        val minimumAdaptation =
            if (pcr != null) {
                8
            } else if (random || !seen[pid]) {
                2
            } else {
                0
            }
        val packets = 1 + (maxOf(0, header.size + payloadSize - (184 - minimumAdaptation)) + 183) / 184
        val output = ByteArray((packets + (if (program) 2 else 0) + (if (clockOnly) 1 else 0)) * 188)
        var offset = 0
        if (program) {
            offset = packetize(output, offset, 0, listOf(slice(POINTER), slice(pat)))
            offset = packetize(output, offset, PMT_PID, listOf(slice(POINTER), slice(pmt)))
            programTime = sample.decodeTimeUs
        }
        if (clockOnly) {
            writeClock(output, offset, (dts - 9000) and TSBinary.MASK)
            offset += 188
        }
        if (clock) pcrTime = sample.decodeTimeUs
        val end = packetize(output, offset, pid, parts, random, pcr)
        check(end == output.size)
        return output
    }

    fun clear() {
        counters.fill(0)
        lastCounters.fill(0)
        seen.fill(false)
        origin = null
        programTime = null
        pcrTime = null
        videoConfig = emptyList()
    }

    private fun payload(sample: TSSample): List<TSVideo.Slice> {
        if (sample.codec == TSCodec.AAC) return listOf(slice(requireNotNull(sample.audio) { "AAC configuration is required" }.adts(sample.data.size)), slice(sample.data))
        val units = TSVideo.slices(sample.data, sample.framing)
        val auditType = if (sample.codec == TSCodec.AVC) 9 else 35
        val result = ArrayList<TSVideo.Slice>(units.size * 2 + videoConfig.size * 2 + 2)

        fun append(unit: TSVideo.Slice) {
            result.add(slice(START_CODE))
            result.add(unit)
        }
        if (units.none { TSVideo.type(it, sample.codec) == auditType }) append(slice(if (sample.codec == TSCodec.AVC) AVC_AUD else HEVC_AUD))
        if (sample.keyframe) {
            videoConfig.forEach { configured ->
                if (units.none { TSVideo.type(it, sample.codec) == TSVideo.type(configured, sample.codec) }) append(slice(configured))
            }
        }
        units.forEach { append(it) }
        return result
    }

    private fun makePMT(): ByteArray {
        val tracks =
            codecs.sortedBy { if (it == TSCodec.AAC) 1 else 0 }.flatMap { codec ->
                val pid = if (codec == TSCodec.AAC) AUDIO_PID else VIDEO_PID
                listOf(codec.streamType.toByte(), (0xe0 or (pid shr 8)).toByte(), pid.toByte(), 0xf0.toByte(), 0)
            }.toByteArray()
        return section(2, byteArrayOf(0, 1, 0xc1.toByte(), 0, 0, (0xe0 or (pcrPID shr 8)).toByte(), pcrPID.toByte(), 0xf0.toByte(), 0) + tracks)
    }

    private fun section(
        id: Int,
        data: ByteArray,
    ): ByteArray {
        val length = data.size + 4
        val section = byteArrayOf(id.toByte(), (0xb0 or (length shr 8)).toByte(), length.toByte()) + data
        val crc = TSBinary.crc(section)
        return section + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun packetize(
        output: ByteArray,
        outputStart: Int,
        pid: Int,
        parts: List<TSVideo.Slice>,
        randomAccess: Boolean = false,
        pcr: Long? = null,
    ): Int {
        var remaining = parts.sumOf { it.size }
        var partIndex = 0
        var partOffset = 0
        var offset = outputStart
        var first = true
        while (remaining > 0) {
            val discontinuity = !seen[pid]
            val minimum =
                if (first && pcr != null) {
                    8
                } else if (first && (randomAccess || discontinuity)) {
                    2
                } else {
                    0
                }
            val size = minOf(184 - minimum, remaining)
            val adaptation = 184 - size
            output.fill(0xff.toByte(), offset, offset + 188)
            output[offset] = 0x47
            output[offset + 1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            output[offset + 2] = pid.toByte()
            output[offset + 3] = ((if (adaptation > 0) 0x30 else 0x10) or counters[pid]).toByte()
            lastCounters[pid] = counters[pid]
            counters[pid] = (counters[pid] + 1) and 15
            if (adaptation > 0) {
                output[offset + 4] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    output[offset + 5] = ((if (first && discontinuity) 0x80 else 0) or (if (first && randomAccess) 0x40 else 0) or (if (first && pcr != null) 0x10 else 0)).toByte()
                    if (first && pcr != null) pcr(output, offset + 6, pcr)
                }
            }
            var count = size
            var destination = offset + 4 + adaptation
            while (count > 0) {
                val part = parts[partIndex]
                val take = minOf(count, part.size - partOffset)
                part.data.copyInto(output, destination, part.offset + partOffset, part.offset + partOffset + take)
                partOffset += take
                destination += take
                count -= take
                if (partOffset == part.size) {
                    partIndex++
                    partOffset = 0
                }
            }
            seen[pid] = true
            remaining -= size
            offset += 188
            first = false
        }
        return offset
    }

    private fun writeClock(
        output: ByteArray,
        offset: Int,
        value: Long,
    ) {
        output.fill(0xff.toByte(), offset, offset + 188)
        output[offset] = 0x47
        output[offset + 1] = (pcrPID shr 8).toByte()
        output[offset + 2] = pcrPID.toByte()
        output[offset + 3] = (0x20 or lastCounters[pcrPID]).toByte()
        counters[pcrPID] = (lastCounters[pcrPID] + 1) and 15
        output[offset + 4] = 183.toByte()
        output[offset + 5] = (0x10 or if (seen[pcrPID]) 0 else 0x80).toByte()
        pcr(output, offset + 6, value)
        seen[pcrPID] = true
    }

    private fun pcr(
        output: ByteArray,
        offset: Int,
        value: Long,
    ) {
        output[offset] = (value shr 25).toByte()
        output[offset + 1] = (value shr 17).toByte()
        output[offset + 2] = (value shr 9).toByte()
        output[offset + 3] = (value shr 1).toByte()
        output[offset + 4] = (((value and 1).toInt() shl 7) or 0x7e).toByte()
        output[offset + 5] = 0
    }

    private fun slice(data: ByteArray): TSVideo.Slice = TSVideo.Slice(data, 0, data.size)

    companion object {
        const val PMT_PID = 4095
        const val VIDEO_PID = 256
        const val AUDIO_PID = 257
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val POINTER = byteArrayOf(0)
        private val AVC_AUD = byteArrayOf(9, 0xf0.toByte())
        private val HEVC_AUD = byteArrayOf(0x46, 1, 0x50)
    }
}
