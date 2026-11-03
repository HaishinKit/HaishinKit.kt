package com.haishinkit.srt.ts

/** Incremental one-program MPEG-TS reader with bounded PSI/PES assemblies. */
internal class TSReader {
    private val partialPacket = ByteArray(188)
    private var partialSize = 0
    private var pmtPID = -1
    private val codecs = mutableMapOf<Int, TSCodec>()
    private val counters = mutableMapOf<Int, Int>()
    private val sections = mutableMapOf<Int, ByteArray>()
    private val pes = mutableMapOf<Int, Assembly>()
    private val buffers = mutableMapOf<Int, Assembly>()
    private val timestamps = mutableMapOf<Int, Long>()
    private var clock: Long? = null

    fun read(data: ByteArray): List<TSSample> {
        val output = mutableListOf<TSSample>()
        var offset = 0
        while (offset < data.size) {
            if (partialSize == 0 && data.size - offset >= 188) {
                if (TSBinary.u8(data, offset) != 0x47) {
                    offset++
                    continue
                }
                packet(data, offset, output)
                offset += 188
            } else {
                val count = minOf(188 - partialSize, data.size - offset)
                data.copyInto(partialPacket, partialSize, offset, offset + count)
                partialSize += count
                offset += count
                if (partialSize == 188) {
                    if (TSBinary.u8(partialPacket, 0) == 0x47) {
                        packet(partialPacket, 0, output)
                        partialSize = 0
                    } else {
                        val next = (1 until 188).firstOrNull { TSBinary.u8(partialPacket, it) == 0x47 }
                        if (next == null) {
                            partialSize = 0
                        } else {
                            partialPacket.copyInto(partialPacket, 0, next, 188)
                            partialSize = 188 - next
                        }
                    }
                }
            }
        }
        return output
    }

    /** Complete zero-length video PES when the caller knows the transport has ended. */
    fun flush(): List<TSSample> {
        val result = mutableListOf<TSSample>()
        pes.toMap().forEach { (pid, assembly) -> complete(pid, assembly, result, allowUnbounded = true) }
        pes.clear()
        return result
    }

    fun clear() {
        partialSize = 0
        pmtPID = -1
        codecs.clear()
        counters.clear()
        sections.clear()
        pes.clear()
        buffers.clear()
        timestamps.clear()
        clock = null
    }

    private fun packet(
        bytes: ByteArray,
        base: Int,
        output: MutableList<TSSample>,
    ) {
        val pid = ((TSBinary.u8(bytes, base + 1) and 31) shl 8) or TSBinary.u8(bytes, base + 2)
        if (pid != 0 && pid != pmtPID && pid !in codecs) return
        val start = TSBinary.u8(bytes, base + 1) and 0x40 != 0
        val control = (TSBinary.u8(bytes, base + 3) shr 4) and 3
        if (control == 0 || TSBinary.u8(bytes, base + 1) and 0x80 != 0 || TSBinary.u8(bytes, base + 3) and 0xc0 != 0) {
            pes.remove(pid)
            sections.remove(pid)
            return
        }
        val end = base + 188
        var offset = base + 4
        var discontinuity = false
        var keyframe = false
        if (control and 2 != 0) {
            val length = TSBinary.u8(bytes, offset)
            if (offset + length + 1 > end) {
                pes.remove(pid)
                return
            }
            if (length > 0) {
                discontinuity = TSBinary.u8(bytes, offset + 1) and 0x80 != 0
                keyframe = TSBinary.u8(bytes, offset + 1) and 0x40 != 0
            }
            offset += length + 1
        }
        if (discontinuity) {
            counters.remove(pid)
            pes.remove(pid)
            sections.remove(pid)
        }
        if (control and 1 == 0 || offset >= end) return
        val counter = TSBinary.u8(bytes, base + 3) and 15
        val previous = counters.put(pid, counter)
        if (previous == counter && !discontinuity) return
        if (previous != null && counter != ((previous + 1) and 15)) {
            pes.remove(pid)
            sections.remove(pid)
            if (!start) return
        }
        if (pid == 0 || pid == pmtPID) {
            section(pid, bytes.copyOfRange(offset, end), start)
            return
        }
        if (start) {
            pes.remove(pid)?.let { complete(pid, it, output, allowUnbounded = true) }
            val assembly = buffers.getOrPut(pid) { Assembly() }
            assembly.reset(keyframe)
            pes[pid] = assembly
        }
        val assembly = pes[pid] ?: return
        if (assembly.size + end - offset > MAX_PES_SIZE) {
            pes.remove(pid)
            return
        }
        assembly.append(bytes, offset, end - offset)
        if (assembly.size >= 6) {
            val length = TSBinary.u16(assembly.data, 4)
            if (length != 0 && assembly.size >= length + 6) {
                pes.remove(pid)
                complete(pid, assembly, output)
            }
        }
    }

    private fun section(
        pid: Int,
        payload: ByteArray,
        start: Boolean,
    ) {
        var offset = 0
        if (start) {
            if (payload.isEmpty()) return
            val pointer = TSBinary.u8(payload, 0)
            if (pointer + 1 > payload.size) {
                sections.remove(pid)
                return
            }
            if (pointer > 0 && sections.containsKey(pid)) consumeSections(pid, sections[pid]!! + payload.copyOfRange(1, pointer + 1))
            sections.remove(pid)
            offset = pointer + 1
        } else if (!sections.containsKey(pid)) {
            return
        }
        consumeSections(pid, (sections[pid] ?: byteArrayOf()) + payload.copyOfRange(offset, payload.size))
    }

    private fun consumeSections(
        pid: Int,
        data: ByteArray,
    ) {
        var offset = 0
        while (data.size - offset >= 3) {
            if (TSBinary.u8(data, offset) == 255) {
                sections.remove(pid)
                return
            }
            val length = 3 + (TSBinary.u16(data, offset + 1) and 0x0fff)
            if (length !in 12..1024) {
                sections.remove(pid)
                return
            }
            if (data.size - offset < length) break
            val table = data.copyOfRange(offset, offset + length)
            if (TSBinary.crc(table) == 0 && TSBinary.u8(table, 5) and 1 == 1) parseSection(pid, table)
            offset += length
        }
        sections[pid] = data.copyOfRange(offset, data.size)
    }

    private fun parseSection(
        pid: Int,
        data: ByteArray,
    ) {
        if (pid == 0 && data[0].toInt() == 0) {
            var offset = 8
            while (offset + 4 <= data.size - 4) {
                if (TSBinary.u16(data, offset) != 0) {
                    val next = TSBinary.u16(data, offset + 2) and 0x1fff
                    if (next != pmtPID) {
                        pmtPID = next
                        codecs.clear()
                        buffers.clear()
                        timestamps.clear()
                        pes.clear()
                        sections.keys.filter { it != 0 }.forEach { sections.remove(it) }
                    }
                    return
                }
                offset += 4
            }
        } else if (pid == pmtPID && data[0].toInt() == 2 && data.size >= 16) {
            val next = mutableMapOf<Int, TSCodec>()
            var offset = 12 + (TSBinary.u16(data, 10) and 0x0fff)
            while (offset + 5 <= data.size - 4) {
                val codec = TSCodec.entries.firstOrNull { it.streamType == TSBinary.u8(data, offset) }
                val elementaryPID = TSBinary.u16(data, offset + 1) and 0x1fff
                if (codec != null && !next.containsValue(codec) && (codec == TSCodec.AAC || next.values.none { it != TSCodec.AAC })) next[elementaryPID] = codec
                offset += 5 + (TSBinary.u16(data, offset + 3) and 0x0fff)
            }
            if (offset != data.size - 4) return
            buffers.keys.filter { codecs[it] != next[it] }.forEach {
                buffers.remove(it)
                timestamps.remove(it)
            }
            pes.keys.filter { codecs[it] != next[it] }.forEach {
                pes.remove(it)
                timestamps.remove(it)
            }
            codecs.clear()
            codecs.putAll(next)
        }
    }

    private fun complete(
        pid: Int,
        assembly: Assembly,
        output: MutableList<TSSample>,
        allowUnbounded: Boolean = false,
    ) {
        val data = assembly.data
        val size = assembly.size
        if (size < 14 || data[0] != 0.toByte() || data[1] != 0.toByte() || data[2] != 1.toByte()) return
        val length = TSBinary.u16(data, 4)
        if (length == 0 && codecs[pid] == TSCodec.AAC) return
        if ((length == 0 && !allowUnbounded) || (length != 0 && size < length + 6)) return
        val end = if (length == 0) size else length + 6
        val flags = TSBinary.u8(data, 7) shr 6
        val header = TSBinary.u8(data, 8)
        if ((TSBinary.u8(data, 6) and 0xc0) != 0x80 || flags !in 2..3 || header < (if (flags == 3) 10 else 5) || 9 + header > end) return
        try {
            val pts = unwrap(TSBinary.timestamp(data, 9), timestamps[pid] ?: clock)
            val dts = if (flags == 3) unwrap(TSBinary.timestamp(data, 14), pts) else pts
            timestamps[pid] = pts
            clock = maxOf(clock ?: pts, pts)
            if (9 + header == end) return
            val payload = data.copyOfRange(9 + header, end)
            val codec = codecs[pid] ?: return
            if (codec == TSCodec.AAC) {
                readAudio(payload, pts, output)
            } else {
                val key = assembly.keyframe || TSVideo.hasKey(payload, codec)
                output.add(TSSample(codec, payload, TSBinary.micros(pts), TSBinary.micros(dts), key))
            }
        } catch (_: IllegalArgumentException) {
            // Drop malformed PES/ADTS, retaining subsequent TS synchronization.
        }
    }

    private fun readAudio(
        data: ByteArray,
        pts: Long,
        output: MutableList<TSSample>,
    ) {
        var offset = 0
        var samples = 0L
        while (offset + 7 <= data.size) {
            if (TSBinary.u8(data, offset) != 255 || TSBinary.u8(data, offset + 1) and 0xf6 != 0xf0) return
            val header = if (TSBinary.u8(data, offset + 1) and 1 != 0) 7 else 9
            val index = (TSBinary.u8(data, offset + 2) shr 2) and 15
            if (index >= TSAudioConfig.RATES.size || TSBinary.u8(data, offset + 6) and 3 != 0) return
            val channels = ((TSBinary.u8(data, offset + 2) and 1) shl 2) or (TSBinary.u8(data, offset + 3) shr 6)
            val config = TSAudioConfig(TSAudioConfig.RATES[index], channels, (TSBinary.u8(data, offset + 2) shr 6) + 1)
            val size = ((TSBinary.u8(data, offset + 3) and 3) shl 11) or (TSBinary.u8(data, offset + 4) shl 3) or (TSBinary.u8(data, offset + 5) shr 5)
            if (size < header || offset + size > data.size) return
            val time = TSBinary.micros(pts) + samples * 1_000_000 / config.sampleRate
            output.add(TSSample(TSCodec.AAC, data.copyOfRange(offset + header, offset + size), time, audio = config, keyframe = true))
            samples += 1024
            offset += size
        }
    }

    private fun unwrap(
        raw: Long,
        reference: Long?,
    ): Long {
        if (reference == null) return raw
        var candidate = (reference and TSBinary.MASK.inv()) or raw
        if (candidate - reference > TSBinary.WRAP / 2) candidate -= TSBinary.WRAP
        if (reference - candidate > TSBinary.WRAP / 2) candidate += TSBinary.WRAP
        return candidate
    }

    /** Reused per PID; only the completed elementary sample is copied for ownership. */
    private class Assembly {
        var data = ByteArray(1024)
        var size = 0
        var keyframe = false

        fun reset(key: Boolean) {
            size = 0
            keyframe = key
        }

        fun append(
            source: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val needed = size + length
            if (needed > data.size) data = data.copyOf(maxOf(needed, data.size * 2))
            source.copyInto(data, size, offset, offset + length)
            size = needed
        }
    }

    companion object {
        private const val MAX_PES_SIZE = 8 * 1024 * 1024
    }
}
