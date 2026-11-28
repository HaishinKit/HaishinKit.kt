package com.haishinkit.rnnoise

import android.media.AudioFormat
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.audio.PcmAudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class RnnoiseAudioProcessorTest {
    @Test fun fragmentedDryPcmPreservesSamplesAndTheFixedDelay() {
        val input = ShortArray(10000) { ((it * 71 % 20000) - 10000).toShort() }
        RnnoiseAudioProcessor(PcmAudioFormat(48000, 1)).use { processor ->
            processor.isBypassed = true
            val actual = process(processor, input, intArrayOf(1, 17, 480, 1024, 133))
            assertEquals(input.size, actual.size)
            for (i in actual.indices) assertEquals(if (i < processor.latencyFrames) 0.toShort() else input[i - processor.latencyFrames], actual[i])
            processor.reset()
            assertTrue(process(processor, ShortArray(3000), intArrayOf(1024)).all { it == 0.toShort() })
        }
    }

    @Test fun wetImpulsePeakMatchesTheReportedDelay() {
        val input = ShortArray(8000).apply { this[3000] = 30000 }
        RnnoiseAudioProcessor(PcmAudioFormat(48000, 1)).use { processor ->
            val output = process(processor, input, intArrayOf(1024, 83, 480))
            val peak = output.indices.maxBy { kotlin.math.abs(output[it].toInt()) }
            assertTrue(kotlin.math.abs(output[peak].toInt()) > 0)
            assertEquals(3000 + processor.latencyFrames, peak)
        }
    }

    @Test fun wetOutputIsIndependentOfCaptureChunkSizes() {
        val input = ShortArray(12000) { (8000 * sin(it * 2 * PI * 220 / 48000)).toInt().toShort() }
        val one = RnnoiseAudioProcessor(PcmAudioFormat(48000, 1)).use { process(it, input, intArrayOf(input.size)) }
        val many = RnnoiseAudioProcessor(PcmAudioFormat(48000, 1)).use { process(it, input, intArrayOf(137, 1024, 29)) }
        assertArrayEquals(one, many)
        assertTrue("RNNoise produced only silence", one.any { it != 0.toShort() })
    }

    @Test fun stereoStatesDoNotLeakIntoTheSilentChannel() {
        val input = ShortArray(12000) { if (it % 2 == 0) (9000 * sin(it * PI * 330 / 48000)).toInt().toShort() else 0 }
        RnnoiseAudioProcessor(PcmAudioFormat(48000, 2)).use {
            val output = process(it, input, intArrayOf(200, 2048, 34))
            assertTrue(output.indices.filter { index -> index % 2 == 1 }.all { index -> output[index] == 0.toShort() })
            assertTrue(output.any { sample -> sample != 0.toShort() })
        }
    }

    @Test fun floatDryPathPreservesFullScaleAndSmallSignals() {
        RnnoiseAudioProcessor(PcmAudioFormat(48000, 1, AudioFormat.ENCODING_PCM_FLOAT)).use { processor ->
            processor.isBypassed = true
            val values = floatArrayOf(-1f, 1f, 0.000001f, -0.000001f, 0f)
            val frames = processor.latencyFrames + values.size
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames) { input.putFloat(values.getOrElse(it) { 0f }) }
            input.flip()
            val output = processor.process(input).order(ByteOrder.nativeOrder())
            output.position(processor.latencyFrames * 4)
            for (value in values) assertEquals(value, output.float, 0f)
        }
    }

    @Test fun floatInputSanitizesNonFiniteSamplesAndPreservesFrameCount() {
        RnnoiseAudioProcessor(PcmAudioFormat(48000, 1, AudioFormat.ENCODING_PCM_FLOAT)).use {
            val input = ByteBuffer.allocateDirect(5000 * 4).order(ByteOrder.nativeOrder())
            repeat(5000) { index -> input.putFloat(if (index % 100 == 0) Float.NaN else 0.1f) }
            input.flip()
            val output = it.process(input).order(ByteOrder.nativeOrder())
            assertEquals(5000 * 4, output.remaining())
            repeat(5000) { assertTrue(output.float.isFinite()) }
        }
    }

    @Test fun rejectsUnsupportedRateBeforeCreatingNativeState() {
        assertThrows(IllegalArgumentException::class.java) { RnnoiseAudioProcessor(PcmAudioFormat(44100, 1)) }
    }

    @Test fun benchmarkTenSecondsOfMonoAudio() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("rnnoiseBenchmark") == "true")
        RnnoiseAudioProcessor(PcmAudioFormat(48000, 1)).use { processor ->
            val input = ByteBuffer.allocateDirect(960).order(ByteOrder.nativeOrder())
            repeat(480) { input.putShort((5000 * sin(it * 2 * PI * 220 / 48000)).toInt().toShort()) }
            input.flip()
            repeat(100) { processor.process(input) }
            val durations = LongArray(1000)
            repeat(1000) { index ->
                val before = processor.processingNanos
                processor.process(input)
                durations[index] = processor.processingNanos - before
            }
            durations.sort()
            val mean = durations.average() / 1e6
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("stream", "\nRNNOISE_BENCHMARK meanMs=$mean p95Ms=${durations[950] / 1e6} maxMs=${durations.last() / 1e6}\n")
                },
            )
            assertTrue("RNNoise exceeds the 10 ms frame budget on this device", mean < 10)
        }
    }

    private fun process(
        processor: RnnoiseAudioProcessor,
        input: ShortArray,
        chunks: IntArray,
    ): ShortArray {
        val result = ShortArray(input.size)
        var offset = 0
        var chunk = 0
        while (offset < input.size) {
            val count = minOf(chunks[chunk++ % chunks.size], input.size - offset)
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.nativeOrder())
            repeat(count) { bytes.putShort(input[offset + it]) }
            bytes.flip()
            val output = processor.process(bytes).order(ByteOrder.nativeOrder())
            repeat(count) { result[offset + it] = output.short }
            offset += count
        }
        return result
    }
}
