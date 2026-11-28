package com.haishinkit.app

import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.MediaType
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.media.source.PcmAudioSource
import com.haishinkit.screen.AssetScreenObject
import com.haishinkit.screen.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Real video/audio asset decoding and mixed output; microphone/projection PCM is synthetic. */
@RunWith(AndroidJUnit4::class)
class AudioCompositionTest {
    @Test fun threeInputsRemainMixedAndSynchronizedAcrossVideoLoops() = exercise(48000, 2)

    @Test fun callersCanSelect44100HzMonoWithoutChangingInputSources() = exercise(44100, 1)

    @Test fun failedInputReopenDoesNotPreventOtherInputsFromRestarting() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val mixer = MediaMixer(context)
            val healthy = ConstantSource(PcmAudioFormat(48000, 1), 1000)
            val failing = ConstantSource(PcmAudioFormat(48000, 1), 2000)
            val latest = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
            val output =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null

                    override fun append(buffer: MediaBuffer) {
                        latest.set(checkNotNull(buffer.payload).getShort(200).toInt())
                    }
                }

            fun awaitValue(expected: Int) {
                val deadline = SystemClock.uptimeMillis() + 3000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (latest.get() == expected) return
                    SystemClock.sleep(5)
                }
                assertEquals(expected, latest.get())
            }
            try {
                mixer.attachAudio(0, healthy).getOrThrow()
                mixer.attachAudio(
                    1,
                    object : PcmAudioSource by failing {
                        override suspend fun open(mixer: MediaMixer): Result<Unit> =
                            if (failing.opens.get() > 0) Result.failure(IllegalStateException("Synthetic input unavailable")) else failing.open(mixer)
                    },
                ).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                awaitValue(3000)
                mixer.stopRunning()
                val deadline = SystemClock.uptimeMillis() + 3000
                while (failing.closes.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(5)
                assertTrue(failing.closes.get() > 0)
                latest.set(Int.MIN_VALUE)
                mixer.startRunning()
                awaitValue(1000)
                assertTrue("Failed reopen was not cleaned up", failing.closes.get() >= 2)
            } finally {
                mixer.stopRunning()
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
        }

    @Test fun gainAndMuteSurviveCaptureRestartAndTrackReplacement() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val mixer = MediaMixer(context)
            val source = ConstantSource(PcmAudioFormat(48000, 1), 1000)
            val latest = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
            val output =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null

                    override fun append(buffer: MediaBuffer) {
                        latest.set(checkNotNull(buffer.payload).getShort(200).toInt())
                    }
                }

            fun await(
                message: String,
                condition: () -> Boolean,
            ) {
                val deadline = SystemClock.uptimeMillis() + 3000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (condition()) return
                    SystemClock.sleep(5)
                }
                throw AssertionError(message)
            }
            try {
                mixer.audioMixerSettings = mixer.audioMixerSettings.copy(tracks = mapOf(0 to com.haishinkit.media.AudioMixerTrackSettings(.25f)))
                mixer.attachAudio(0, source).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                await("Initial gain missing") { latest.get() == 250 }
                mixer.stopRunning()
                await("Input did not stop") { source.closes.get() > 0 }
                latest.set(Int.MIN_VALUE)
                mixer.startRunning()
                await("Gain reset after restart") { source.opens.get() >= 2 && latest.get() == 250 }
                mixer.audioMixerSettings = mixer.audioMixerSettings.copy(tracks = mapOf(0 to com.haishinkit.media.AudioMixerTrackSettings(.5f, true)))
                await("Track mute missing") { latest.get() == 0 }
                val closes = source.closes.get()
                mixer.stopRunning()
                await("Muted input did not stop") { source.closes.get() > closes }
                latest.set(Int.MIN_VALUE)
                mixer.startRunning()
                await("Mute reset after restart") { latest.get() == 0 }
                mixer.audioMixerSettings = mixer.audioMixerSettings.copy(tracks = mapOf(0 to com.haishinkit.media.AudioMixerTrackSettings(.5f)))
                await("Unmute/gain missing") { latest.get() == 500 }
                repeat(10) {
                    mixer.stopRunning()
                    mixer.startRunning()
                }
                latest.set(Int.MIN_VALUE)
                await("Rapid restart stranded the mixer") { latest.get() == 500 }
                mixer.attachAudio(0, null).getOrThrow()
                assertTrue("The continuing silent clock must still advertise audio", mixer.hasAudio)
                await("Detached input remains audible") { latest.get() == 0 }
                mixer.attachAudio(1, ConstantSource(PcmAudioFormat(48000, 1), 2000)).getOrThrow()
                await("Replacement inherited stale gain") { latest.get() == 2000 }
            } finally {
                mixer.stopRunning()
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
        }

    private fun exercise(
        rate: Int,
        channels: Int,
    ) =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val previousFactory = Screen.factory
            Screen.factory = { Screen.createOpenGL(it) }
            val file = File(context.cacheDir, "audio-composition-$rate.mp4")
            instrumentation.context.assets.open("audio-mixing.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val mixer = MediaMixer(context)
            mixer.audioMixerSettings = AudioMixerSettings(sampleRate = rate, channels = channels)
            val error = AtomicReference<Throwable?>()
            val asset =
                AssetScreenObject(context).apply {
                    frame = Rect(0, 0, 64, 48)
                    isLooping = true
                    onError = { error.set(it) }
                    startReading(Uri.fromFile(file))
                }
            val waves = Array(2) { ShortArray(rate / 2 * channels) }
            val offsets = IntArray(2)
            val finished = BooleanArray(2)
            val captured = CountDownLatch(2)
            val afterClose = CountDownLatch(3)
            val closed = AtomicBoolean(false)
            val timestampFailure = AtomicBoolean(false)
            val output =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null
                    private var previousVideo = 0L
                    private var loop = 0
                    private var previous = -1L

                    override fun append(buffer: MediaBuffer) {
                        if (buffer.type != MediaType.AUDIO) return
                        if (buffer.timestamp <= previous) timestampFailure.set(true)
                        previous = buffer.timestamp
                        val pcm = checkNotNull(buffer.payload)
                        val video = asset.currentTimeUs
                        if (previousVideo > 900_000 && video < 300_000) loop++
                        previousVideo = video
                        val window = if (video in 300_000..900_000 && loop < 2) loop else -1
                        if (window >= 0 && !finished[window]) {
                            var position = 0
                            while (position < pcm.remaining() && offsets[window] < waves[window].size) {
                                waves[window][offsets[window]++] = pcm.getShort(position)
                                position += 2
                            }
                            if (offsets[window] == waves[window].size) {
                                finished[window] = true
                                captured.countDown()
                            }
                        }
                        if (closed.get() && abs(pcm.getShort(200).toInt() - 1500) < 5) afterClose.countDown()
                    }
                }
            try {
                mixer.screen.frame = Rect(0, 0, 64, 48)
                mixer.screen.addChild(asset)
                mixer.attachAudio(0, ConstantSource(PcmAudioFormat(16000, 1), 1000)).getOrThrow()
                mixer.attachAudio(1, ConstantSource(PcmAudioFormat(48000, 2), 500)).getOrThrow()
                mixer.attachAudio(asset.audioTrack, asset.audioSource).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                assertTrue("Mixed audio did not reach both loop windows", captured.await(8, TimeUnit.SECONDS))
                error.get()?.let { throw AssertionError("Asset playback failed", it) }
                assertTrue(!timestampFailure.get())
                for (wave in waves) {
                    val mean = wave.map { it.toDouble() }.average()
                    assertEquals("Inputs were interleaved instead of mixed", 1500.0, mean, 200.0)
                    val left440 = amplitude(wave, rate, channels, 0, 440)
                    val right660 = amplitude(wave, rate, channels, channels - 1, 660)
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        0,
                        android.os.Bundle().apply {
                            putString("stream", "\nComposition $rate/${channels}ch: mean=$mean left440=$left440 right660=$right660\n")
                        },
                    )
                    assertTrue("Missing asset audio: $left440", left440 > if (channels == 2) 2500 else 1200)
                    assertTrue("Missing right/downmixed channel: $right660", right660 > if (channels == 2) 2500 else 1200)
                }
                closed.set(true)
                asset.close()
                assertTrue("Closing the asset also stopped other inputs", afterClose.await(3, TimeUnit.SECONDS))
            } finally {
                asset.close()
                mixer.stopRunning()
                mixer.unregisterOutput(output)
                mixer.dispose()
                Screen.factory = previousFactory
                file.delete()
            }
        }

    private fun amplitude(
        pcm: ShortArray,
        rate: Int,
        channels: Int,
        channel: Int,
        frequency: Int,
    ): Double {
        var real = 0.0
        var imaginary = 0.0
        val frames = pcm.size / channels
        for (i in 0 until frames) {
            val phase = 2 * Math.PI * frequency * i / rate
            real += pcm[i * channels + channel] * cos(phase)
            imaginary += pcm[i * channels + channel] * sin(phase)
        }
        return sqrt(real * real + imaginary * imaginary) * 2 / frames
    }

    internal class ConstantSource(override val audioFormat: PcmAudioFormat, value: Int) : PcmAudioSource {
        @Volatile override var isMuted = false
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        private val pcm =
            ByteBuffer.allocateDirect(audioFormat.sampleRate / 100 * audioFormat.channels * 2).order(ByteOrder.nativeOrder()).apply {
                while (remaining() >= 2) putShort(value.toShort())
                flip()
            }

        override suspend fun open(mixer: MediaMixer): Result<Unit> = Result.success(Unit).also { opens.incrementAndGet() }

        override suspend fun close(): Result<Unit> = Result.success(Unit).also { closes.incrementAndGet() }

        override fun read(track: Int): MediaBuffer {
            SystemClock.sleep(10)
            return MediaBuffer(MediaType.AUDIO, track, pcm)
        }
    }
}
