package com.haishinkit.media.audio

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.AudioMixerTrackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NativeAudioMixerTest {
    @Test fun heapAndUnalignedBuffersKeepTheirPositionsAndFormat() {
        NativeAudioMixer(AudioMixerSettings()).use { mixer ->
            mixer.add(0)
            val heap =
                ByteBuffer.allocate(1922).order(ByteOrder.nativeOrder()).apply {
                    putShort(99)
                    repeat(960) { putShort(2000) }
                    flip()
                    position(2)
                }
            mixer.append(0, heap, PcmAudioFormat(48000, 2))
            assertEquals(2, heap.position())
            assertEquals(2000, mixer.render(false).getShort(0).toInt())
            val unaligned =
                ByteBuffer.allocateDirect(3841).order(ByteOrder.nativeOrder()).apply {
                    put(99.toByte())
                    repeat(960) { putFloat(.25f) }
                    flip()
                    position(1)
                }
            mixer.append(0, unaligned, PcmAudioFormat(48000, 2, android.media.AudioFormat.ENCODING_PCM_FLOAT))
            assertEquals(1, unaligned.position())
            assertEquals(8192, mixer.render(false).getShort(0).toInt())
        }
    }

    @Test fun mixedPcmGainMuteClippingAndDiscontinuitiesCrossJni() {
        NativeAudioMixer(AudioMixerSettings()).use { mixer ->
            mixer.add(0)
            mixer.add(1)
            mixer.add(2)
            val a = pcm(480, 10000)
            val b = pcm(960, -1000)
            mixer.append(0, a, PcmAudioFormat(48000, 1))
            mixer.append(1, b, PcmAudioFormat(48000, 2))
            mixer.configure(0, AudioMixerTrackSettings(.5f))
            val mixed = mixer.render(false).asShortBuffer()
            while (mixed.hasRemaining()) assertEquals(4000, mixed.get().toInt())
            mixer.append(0, a, PcmAudioFormat(48000, 1))
            mixer.configure(0, AudioMixerTrackSettings(isMuted = true))
            assertEquals(0, mixer.render(false).getShort(0).toInt())
            mixer.configure(0, AudioMixerTrackSettings())
            assertEquals(0, mixer.render(false).getShort(0).toInt())
            val loud = pcm(960, 30000)
            for (id in 0..2) mixer.append(id, loud, PcmAudioFormat(48000, 2))
            assertEquals(32767, mixer.render(false).getShort(0).toInt())
            mixer.append(0, a, PcmAudioFormat(48000, 1))
            mixer.clear(0)
            assertEquals(0, mixer.render(false).getShort(0).toInt())
        }
    }

    @Test fun producerAndConsumerCallsCanRaceWithDetachAndClose() {
        val mixer = NativeAudioMixer(AudioMixerSettings())
        mixer.add(0)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val producer =
            Thread {
                try {
                    val input = pcm(960, 1000)
                    repeat(1000) { mixer.append(0, input, PcmAudioFormat(48000, 2)) }
                } catch (cause: Throwable) {
                    error.set(cause)
                }
            }
        producer.start()
        repeat(100) { mixer.render(false) }
        mixer.remove(0)
        mixer.close()
        producer.join(5000)
        assertTrue(!producer.isAlive)
        error.get()?.let { throw AssertionError("Concurrent native append failed", it) }
    }

    @Test fun threeSourceJniPerformance() {
        NativeAudioMixer(AudioMixerSettings()).use { mixer ->
            mixer.add(0)
            mixer.add(1)
            mixer.add(2)
            val mic = pcm(160, 1000)
            val asset = pcm(882, 1000)
            val projection = pcm(960, 1000)
            val micFormat = PcmAudioFormat(16000, 1)
            val assetFormat = PcmAudioFormat(44100, 2)
            val projectionFormat = PcmAudioFormat(48000, 2)
            val times = LongArray(3000)
            repeat(3500) { index ->
                val start = System.nanoTime()
                mixer.append(0, mic, micFormat)
                mixer.append(1, asset, assetFormat)
                mixer.append(2, projection, projectionFormat)
                mixer.render(false)
                if (index >= 500) times[index - 500] = System.nanoTime() - start
            }
            times.sort()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("stream", "\nAudio JNI 3-source/10ms: p50=${times[1500]}ns p95=${times[2850]}ns p99=${times[2970]}ns\n")
                },
            )
            // Functional assertions remain stable across CI/device load; timing is reported, not gated.
            assertTrue(times[0] > 0)
        }
    }

    private fun pcm(
        samples: Int,
        value: Int,
    ): ByteBuffer =
        ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(samples) { putShort(value.toShort()) }
            flip()
        }
}
