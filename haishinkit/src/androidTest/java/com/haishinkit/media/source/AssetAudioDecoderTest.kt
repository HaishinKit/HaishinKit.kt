package com.haishinkit.media.source

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.audio.NativeAudioMixer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class AssetAudioDecoderTest {
    @Test fun sixChannelAacIsDownmixedByTheDecoder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "asset-audio-surround.m4a")
        instrumentation.context.assets.open("audio-surround.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        val error = AtomicReference<Throwable?>()
        val observedChannels = java.util.concurrent.atomic.AtomicInteger()
        NativeAudioMixer(AudioMixerSettings()).use { mixer ->
            mixer.add(0)
            val clock = AssetPlaybackClock()
            val anchor = System.nanoTime() + 100_000_000
            clock.begin(0, anchor)
            val decoder =
                AssetAudioDecoder(
                    context,
                    Uri.fromFile(file),
                    clock,
                    null,
                    pcm = { pcm, format ->
                        observedChannels.set(format.channels)
                        mixer.append(0, pcm, format)
                    },
                    discontinuity = { mixer.clear(0) },
                    error = { error.set(it) },
                )
            var peak = 0
            try {
                decoder.start()
                repeat(80) { tick ->
                    val deadline = anchor + tick * 10_000_000L
                    while (System.nanoTime() < deadline) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(1))
                    val pcm = mixer.render(false).asShortBuffer()
                    while (pcm.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(pcm.get().toInt()))
                }
                error.get()?.let { throw AssertionError("Surround downmix failed", it) }
                org.junit.Assert.assertEquals(2, observedChannels.get())
                assertTrue("Surround content is silent", peak > 500)
            } finally {
                clock.active = false
                decoder.cancel()
                decoder.awaitRelease()
                file.delete()
            }
        }
    }

    @Test fun realAacDecodingResamplesStereoAndRewindsWithTheVideoClock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "asset-audio-clock.mp4")
        instrumentation.context.assets.open("audio-mixing.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val error = AtomicReference<Throwable?>()
        NativeAudioMixer(AudioMixerSettings()).use { mixer ->
            mixer.add(0)
            val clock = AssetPlaybackClock()
            val decoder =
                AssetAudioDecoder(
                    context,
                    Uri.fromFile(file),
                    clock,
                    null,
                    pcm = { pcm, format -> mixer.append(0, pcm, format) },
                    discontinuity = { mixer.clear(0) },
                    error = { error.set(it) },
                )
            val anchor = System.nanoTime() + 100_000_000
            clock.begin(0, anchor)
            val first = Array(2) { ArrayList<Short>() }
            val second = Array(2) { ArrayList<Short>() }
            try {
                decoder.start()
                for (tick in 0 until 240) {
                    val deadline = anchor + tick * 10_000_000L
                    if (tick == 120) clock.begin(0, deadline)
                    while (System.nanoTime() < deadline) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(1))
                    val pcm = mixer.render(false).asShortBuffer()
                    val capture =
                        if (tick in 30..89) {
                            first
                        } else if (tick in 150..209) {
                            second
                        } else {
                            null
                        }
                    if (capture != null) {
                        repeat(480) {
                            capture[0].add(pcm.get())
                            capture[1].add(pcm.get())
                        }
                    }
                }
                error.get()?.let { throw AssertionError("Asset audio failed", it) }
                for (pass in listOf(first, second)) {
                    val left = amplitude(pass[0], 440)
                    val right = amplitude(pass[1], 660)
                    assertTrue("Left 440Hz missing: $left", left > 2000)
                    assertTrue("Right 660Hz missing: $right", right > 2000)
                    assertTrue(left > amplitude(pass[0], 660) * 5)
                    assertTrue(right > amplitude(pass[1], 440) * 5)
                }
            } finally {
                clock.active = false
                decoder.cancel()
                decoder.awaitRelease()
                file.delete()
            }
        }
    }

    private fun amplitude(
        samples: List<Short>,
        frequency: Int,
    ): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in samples.indices) {
            val phase = 2 * Math.PI * frequency * i / 48000
            real += samples[i] * cos(phase)
            imaginary += samples[i] * sin(phase)
        }
        return sqrt(real * real + imaginary * imaginary) * 2 / samples.size
    }
}
