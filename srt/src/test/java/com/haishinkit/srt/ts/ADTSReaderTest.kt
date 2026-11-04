package com.haishinkit.srt.ts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ADTSReaderTest {
    private val config = TSAudioConfig(48000, 2)
    private val raw = ByteArray(40) { 0x22 }
    private val frame = config.adts(raw.size) + raw

    @Test
    fun everyHeaderAndPayloadSplitPreservesTheOriginalFrameTime() {
        for (split in 1 until frame.size) {
            val reader = ADTSReader()
            assertTrue(reader.read(frame.copyOfRange(0, split), 123_000).isEmpty())
            val output = reader.read(frame.copyOfRange(split, frame.size), null).single()
            assertArrayEquals(raw, output.data)
            assertEquals(123_000L, output.presentationTimeUs)
        }
    }

    @Test
    fun carryTimeIsRetainedAndNewPesAnchorAppliesToTheNextFrame() {
        val reader = ADTSReader()
        reader.read(frame.copyOfRange(0, 4), 1_000_000)
        val output = reader.read(frame.copyOfRange(4, frame.size) + frame, 2_000_000)
        assertEquals(listOf(1_000_000L, 2_000_000L), output.map { it.presentationTimeUs })
        assertTrue(output.all { it.data.contentEquals(raw) })
    }

    @Test
    fun timestampsWithoutPtsDoNotAccumulateFractionalSampleRounding() {
        val reader = ADTSReader()
        val config = TSAudioConfig(44100, 1)
        val frame = config.adts(raw.size) + raw
        repeat(1000) { index ->
            val sample = reader.read(frame, if (index == 0) 1_000_000 else null).single()
            assertEquals(1_000_000 + index * 1024L * 1_000_000 / 44100, sample.presentationTimeUs)
        }
    }

    @Test
    fun tsReaderCarriesHeadersAcrossPesAndAcceptsContinuationWithoutPts() {
        val reader = seededReader()
        assertTrue(reader.read(pes(frame.copyOfRange(0, 4), 90_000, 1)).isEmpty())
        val first = reader.read(pes(frame.copyOfRange(4, frame.size), null, 2)).single()
        assertEquals(1_000_000L, first.presentationTimeUs)
        assertArrayEquals(raw, first.data)
        assertEquals(1_021_333L, reader.read(pes(frame, null, 3)).single().presentationTimeUs)
    }

    @Test
    fun continuityLossClearsPartialFrameAndResynchronizesFollowingFrames() {
        val reader = seededReader()
        reader.read(pes(frame.copyOfRange(0, 20), 90_000, 1))
        val output = reader.read(pes(frame.copyOfRange(20, frame.size) + frame, 180_000, 3))
        assertEquals(1, output.size)
        assertEquals(2_000_000L, output.single().presentationTimeUs)
        assertArrayEquals(raw, output.single().data)
    }

    private fun seededReader(): TSReader =
        TSReader().apply {
            read(TSWriter(setOf(TSCodec.AAC)).write(TSSample(TSCodec.AAC, raw, 0, audio = config)))
        }

    private fun pes(
        payload: ByteArray,
        pts: Long?,
        counter: Int,
    ): ByteArray {
        val timestamp = pts?.let { TSBinary.timestamp(it, 0x20) } ?: byteArrayOf()
        val length = payload.size + 3 + timestamp.size
        val data = byteArrayOf(0, 0, 1, 0xc0.toByte(), (length shr 8).toByte(), length.toByte(), 0x80.toByte(), if (pts == null) 0 else 0x80.toByte(), timestamp.size.toByte()) + timestamp + payload
        require(data.size <= 182)
        return ByteArray(188) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[1] = 0x41
            this[2] = 1
            this[3] = (0x30 or counter).toByte()
            this[4] = (183 - data.size).toByte()
            this[5] = 0
            data.copyInto(this, 188 - data.size)
        }
    }
}
