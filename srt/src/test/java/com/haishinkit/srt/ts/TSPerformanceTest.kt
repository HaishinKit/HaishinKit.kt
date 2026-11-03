package com.haishinkit.srt.ts

import org.junit.Assert.assertEquals
import org.junit.Test

/** Repeatable local throughput/allocation measurement; no wall-clock threshold. */
class TSPerformanceTest {
    @Test
    fun measureVideoMuxAndDemux() {
        val sample = TSSample(TSCodec.AVC, TSVideo.annexB(listOf(ByteArray(32_000) { 0x55 }.apply { this[0] = 0x65 })), 0, keyframe = true)
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedMethod = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        repeat(20) { TSReader().read(TSWriter(setOf(TSCodec.AVC)).write(sample)) }
        val allocated = allocatedMethod.invoke(bean, thread) as Long
        val started = System.nanoTime()
        val writer = TSWriter(setOf(TSCodec.AVC))
        val reader = TSReader()
        var count = 0
        repeat(100) { count += reader.read(writer.write(sample.copy(presentationTimeUs = it * 33_333L, decodeTimeUs = it * 33_333L))).size }
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        val bytes = (allocatedMethod.invoke(bean, thread) as Long) - allocated
        assertEquals(100, count)
        println("TS_BENCHMARK frames=$count elapsedMs=$elapsed allocatedBytes=$bytes")
    }
}
