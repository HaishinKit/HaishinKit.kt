package com.haishinkit.srt.ts

import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTSFixtureTest {
    @Test
    fun readsSwiftAvcAacAndStereoFixtures() {
        listOf("sample.ts", "stereo.ts").forEach { name ->
            val samples = read(name)
            val audio = samples.filter { it.codec == TSCodec.AAC }
            val video = samples.filter { it.codec == TSCodec.AVC }
            assertTrue("No AAC samples in $name", audio.size > 10)
            assertTrue("No H264 samples in $name", video.size > 10)
            assertTrue(video.any { it.keyframe })
            assertTrue(video.all { it.data.isNotEmpty() })
            if (name == "stereo.ts") assertTrue(audio.all { it.audio!!.channels == 2 })
        }
    }

    @Test
    fun readsResolutionChangesFromSwiftFixture() {
        val sizes =
            read("resolution-change.ts").filter { it.codec == TSCodec.AVC }.flatMap { sample ->
                TSVideo.configuration(TSVideo.units(sample.data), sample.codec).filter { TSVideo.type(it, sample.codec) == 7 }.map {
                    TSVideo.dimensions(it, sample.codec)
                }
            }.toSet()
        println("SWIFT_TS_RESOLUTIONS=$sizes")
        assertTrue("Expected multiple in-band SPS resolutions", sizes.size >= 2)
    }

    @Test
    fun remuxesSwiftSamplesWithoutChangingElementaryPayloads() {
        val samples = read("stereo.ts")
        val writer = TSWriter(setOf(TSCodec.AVC, TSCodec.AAC))
        val reader = TSReader()
        val remuxed = mutableListOf<TSSample>()
        samples.forEach { remuxed.addAll(reader.read(writer.write(it))) }
        remuxed.addAll(reader.flush())
        val originalAudio = samples.filter { it.codec == TSCodec.AAC }
        val resultAudio = remuxed.filter { it.codec == TSCodec.AAC }
        assertTrue(resultAudio.size == originalAudio.size)
        assertTrue(originalAudio.indices.all { originalAudio[it].data.contentEquals(resultAudio[it].data) })
        val originalVideo = samples.filter { it.codec == TSCodec.AVC }.map { TSVideo.units(it.data).filter { unit -> TSVideo.type(unit, it.codec) in 1..5 } }
        val resultVideo = remuxed.filter { it.codec == TSCodec.AVC }.map { TSVideo.units(it.data).filter { unit -> TSVideo.type(unit, it.codec) in 1..5 } }
        assertTrue(originalVideo.size == resultVideo.size)
        assertTrue(originalVideo.indices.all { index -> originalVideo[index].size == resultVideo[index].size && originalVideo[index].indices.all { originalVideo[index][it].contentEquals(resultVideo[index][it]) } })
    }

    private fun read(name: String): List<TSSample> {
        val reader = TSReader()
        val samples = mutableListOf<TSSample>()
        requireNotNull(javaClass.getResourceAsStream("/ts/$name")).use { input ->
            val buffer = ByteArray(1316)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                samples.addAll(reader.read(buffer.copyOf(size)))
            }
        }
        samples.addAll(reader.flush())
        return samples
    }
}
