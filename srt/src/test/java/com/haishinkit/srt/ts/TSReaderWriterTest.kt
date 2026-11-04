package com.haishinkit.srt.ts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TSReaderWriterTest {
    @Test
    fun clockOnlyPacketsPreserveContinuityBeforeVideoStarts() {
        val writer = TSWriter(setOf(TSCodec.AVC, TSCodec.AAC))
        val config = TSAudioConfig(48000, 2)
        val clocks =
            (0..3).flatMap { index ->
                writer.write(TSSample(TSCodec.AAC, byteArrayOf(1), index * 20_000L, audio = config))
                    .toList().chunked(188).filter { packet ->
                        ((packet[1].toInt() and 31) shl 8 or (packet[2].toInt() and 255)) == TSWriter.VIDEO_PID
                    }
            }
        assertEquals(4, clocks.size)
        assertTrue(clocks.all { it[3].toInt() and 15 == 0 })
        val video =
            writer.write(TSSample(TSCodec.AVC, TSVideo.annexB(listOf(byteArrayOf(0x65, 7))), 80_000, keyframe = true))
                .toList().chunked(188).first { packet ->
                    ((packet[1].toInt() and 31) shl 8 or (packet[2].toInt() and 255)) == TSWriter.VIDEO_PID
                }
        assertEquals(1, video[3].toInt() and 15)
    }

    @Test
    fun lengthPrefixedNalIsNotMistakenForAnnexBStartCode() {
        val unit = ByteArray(300) { 0x55 }.apply { this[0] = 0x65 }
        val data = byteArrayOf(0, 0, 1, 44) + unit
        assertArrayEquals(unit, TSVideo.units(data, TSVideo.Framing.LENGTH_PREFIXED).single())
    }

    @Test
    fun ambiguousThreeByteAnnexBStartCodePreservesIdrHeaderThroughRemux() {
        val unit = ByteArray(358) { 0x55 }.apply { this[0] = 0x65 }
        val annexB = byteArrayOf(0, 0, 1) + unit
        assertArrayEquals(unit, TSVideo.units(annexB, TSVideo.Framing.ANNEX_B).single())
        assertTrue(TSVideo.hasKey(annexB, TSCodec.AVC, TSVideo.Framing.ANNEX_B))
        val wire = TSWriter(setOf(TSCodec.AVC)).write(TSSample(TSCodec.AVC, annexB, 0))
        val decoded = TSReader().read(wire).single()
        assertTrue(decoded.keyframe)
        assertArrayEquals(unit, TSVideo.units(decoded.data, decoded.framing).last())
    }

    @Test
    fun explicitlyLengthPrefixedVideoIsConvertedEvenWhenItsLengthLooksLikeAStartCode() {
        val unit = ByteArray(300) { 0x55 }.apply { this[0] = 0x65 }
        val prefixed = byteArrayOf(0, 0, 1, 44) + unit
        val wire = TSWriter(setOf(TSCodec.AVC)).write(TSSample(TSCodec.AVC, prefixed, 0, framing = TSVideo.Framing.LENGTH_PREFIXED))
        val decoded = TSReader().read(wire).single()
        assertTrue(decoded.keyframe)
        assertArrayEquals(unit, TSVideo.units(decoded.data, decoded.framing).last())
    }

    @Test
    fun crcMatchesMpeg2KnownVector() {
        assertEquals(0x0376e6e7, TSBinary.crc("123456789".toByteArray()))
    }

    @Test
    fun audioOnlyUsesAdtsAndPreservesRawFramesAcrossArbitraryChunks() {
        val writer = TSWriter(setOf(TSCodec.AAC))
        val reader = TSReader()
        val config = TSAudioConfig(48000, 2)
        val raw = ByteArray(127) { (it + 3).toByte() }
        val wire = writer.write(TSSample(TSCodec.AAC, raw, 2_000_000, audio = config))
        val output = wire.toList().chunked(17).flatMap { reader.read(it.toByteArray()) }
        assertEquals(1, output.size)
        assertArrayEquals(raw, output.single().data)
        assertEquals(config, output.single().audio)
        assertEquals(100_000L, output.single().presentationTimeUs)
        assertEquals(0, wire.size % 188)
    }

    @Test
    fun videoCarriesParameterSetsPcrAndPtsDts() {
        val writer = TSWriter(setOf(TSCodec.AVC))
        val sps = hex("6742000af841a2")
        val pps = hex("68ce06e2")
        writer.setVideoConfig(listOf(sps, pps))
        val wire = writer.write(TSSample(TSCodec.AVC, TSVideo.annexB(listOf(byteArrayOf(0x65, 2, 3))), 2_033_333, 2_000_000, true))
        val packets = wire.toList().chunked(188)
        val video = packets.first { ((it[1].toInt() and 31) shl 8 or (it[2].toInt() and 255)) == 256 }
        assertTrue(video[5].toInt() and 0x10 != 0)
        val sample = TSReader().read(wire).single()
        val units = TSVideo.units(sample.data)
        assertTrue(units.any { it.contentEquals(sps) })
        assertTrue(units.any { it.contentEquals(pps) })
        assertTrue(sample.keyframe)
        assertEquals(100_000L, sample.decodeTimeUs)
        assertEquals(133_322L, sample.presentationTimeUs)
    }

    @Test
    fun videoAndAudioKeepOneSharedClock() {
        val writer = TSWriter(setOf(TSCodec.AVC, TSCodec.AAC))
        val reader = TSReader()
        val video = writer.write(TSSample(TSCodec.AVC, TSVideo.annexB(listOf(byteArrayOf(0x65, 7))), 5_000_000, keyframe = true))
        val audio = writer.write(TSSample(TSCodec.AAC, byteArrayOf(1, 2), 5_020_000, audio = TSAudioConfig(44100, 1)))
        val samples = reader.read(video + audio)
        assertEquals(2, samples.size)
        assertEquals(20_000L, samples[1].presentationTimeUs - samples[0].presentationTimeUs)
    }

    @Test
    fun zeroLengthLargeVideoPesFlushesAtNextAccessUnitAndEnd() {
        val writer = TSWriter(setOf(TSCodec.HEVC))
        val reader = TSReader()
        val unit =
            ByteArray(70_000) { 0x55 }.apply {
                this[0] = 0x26
                this[1] = 1
            }
        assertTrue(reader.read(writer.write(TSSample(TSCodec.HEVC, TSVideo.annexB(listOf(unit)), 0, keyframe = true))).isEmpty())
        val first = reader.read(writer.write(TSSample(TSCodec.HEVC, TSVideo.annexB(listOf(unit)), 33_333, keyframe = true)))
        assertEquals(1, first.size)
        assertArrayEquals(unit, TSVideo.units(first.single().data).last())
        assertEquals(1, reader.flush().size)
    }

    @Test
    fun packetLossDropsIncompletePesAndRecoversAtNextStart() {
        val writer = TSWriter(setOf(TSCodec.AVC))
        val reader = TSReader()
        val unit = TSVideo.annexB(listOf(ByteArray(1000) { 0x55 }.apply { this[0] = 0x65 }))
        val wire = writer.write(TSSample(TSCodec.AVC, unit, 0, keyframe = true))
        val corrupted = wire.copyOfRange(0, 3 * 188) + wire.copyOfRange(4 * 188, wire.size)
        assertTrue(reader.read(corrupted).isEmpty())
        assertEquals(1, reader.read(writer.write(TSSample(TSCodec.AVC, unit, 33_333, keyframe = true))).size)
    }

    @Test
    fun validatesPsiCrcAndRejectsScrambledPackets() {
        val writer = TSWriter(setOf(TSCodec.AAC))
        val sample = TSSample(TSCodec.AAC, byteArrayOf(1), 0, audio = TSAudioConfig(48000, 1))
        val data = writer.write(sample)
        val damaged = data.copyOf().apply { this[187] = (this[187].toInt() xor 1).toByte() }
        assertTrue(TSReader().read(damaged).isEmpty())
        val scrambled = data.copyOf().apply { this[2 * 188 + 3] = (this[2 * 188 + 3].toInt() or 0x80).toByte() }
        assertTrue(TSReader().read(scrambled).isEmpty())
    }

    @Test
    fun unwrapsPtsAcrossThirtyThreeBitBoundary() {
        val writer = TSWriter(setOf(TSCodec.AAC))
        val reader = TSReader()
        val config = TSAudioConfig(48000, 1)
        reader.read(writer.write(TSSample(TSCodec.AAC, byteArrayOf(1), 0, audio = config)))
        val near = TSBinary.micros(TSBinary.WRAP - 18_000)
        val before = reader.read(writer.write(TSSample(TSCodec.AAC, byteArrayOf(2), near, audio = config))).single()
        val after = reader.read(writer.write(TSSample(TSCodec.AAC, byteArrayOf(3), near + 200_000, audio = config))).single()
        assertTrue(kotlin.math.abs(200_000L - (after.presentationTimeUs - before.presentationTimeUs)) <= 12)
    }

    @Test
    fun extractsSpsDimensionsFromExistingReferenceFixtures() {
        assertEquals(128 to 96, TSVideo.dimensions(hex("6742000af841a2"), TSCodec.AVC))
        assertEquals(1920 to 1080, TSVideo.dimensions(hex("67640028acd940780227e5c05a808080a0000003002000000791e30632c0"), TSCodec.AVC))
        val hevc = hex("4201010160000003000003000003000003005da006c201e1cde8b4aec92ee6a0202020571429")
        assertEquals(854 to 480, TSVideo.dimensions(hevc, TSCodec.HEVC))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
