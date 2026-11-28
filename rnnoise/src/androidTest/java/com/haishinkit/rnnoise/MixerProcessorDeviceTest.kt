package com.haishinkit.rnnoise

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.MediaType
import com.haishinkit.media.audio.AudioProcessor
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.media.source.PcmAudioSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MixerProcessorDeviceTest {
    @Test fun processorFailureStopsOnlyItsInputAndOutputKeepsTheSelectedFormat() =
        runBlocking {
            val mixer = MediaMixer(InstrumentationRegistry.getInstrumentation().targetContext)
            mixer.audioMixerSettings = AudioMixerSettings(sampleRate = 44100, channels = 2)
            val healthy = Source()
            val filtered = Source()
            val created = AtomicInteger()
            val closed = AtomicInteger()
            val outputCount = AtomicInteger()
            val invalid = AtomicInteger()
            val output =
                object : MediaOutput {
                    override var dataSource: WeakReference<MediaOutputDataSource>? = null

                    override fun append(buffer: MediaBuffer) {
                        if (buffer.payload?.remaining() != 441 * 2 * 2) invalid.incrementAndGet()
                        outputCount.incrementAndGet()
                    }
                }
            try {
                mixer.attachAudio(0, healthy).getOrThrow()
                mixer.attachAudio(
                    1,
                    filtered,
                    AudioProcessor.Factory { format ->
                        created.incrementAndGet()
                        val rnnoise = RnnoiseAudioProcessor(format)
                        object : AudioProcessor by rnnoise {
                            private var calls = 0

                            override fun process(input: ByteBuffer): ByteBuffer {
                                if (++calls == 10) error("Injected filter failure")
                                return rnnoise.process(input)
                            }

                            override fun close() {
                                rnnoise.close()
                                closed.incrementAndGet()
                            }
                        }
                    },
                ).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (closed.get() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertEquals(1, created.get())
                assertEquals(1, closed.get())
                assertTrue(filtered.closes.get() > 0)
                val healthyBefore = healthy.reads.get()
                val outputBefore = outputCount.get()
                SystemClock.sleep(300)
                assertTrue(healthy.reads.get() > healthyBefore)
                assertTrue(outputCount.get() > outputBefore)
                assertEquals(0, invalid.get())
            } finally {
                mixer.stopRunning()
                mixer.attachAudio(0, null)
                mixer.attachAudio(1, null)
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
            assertEquals(1, closed.get())
        }

    private class Source : PcmAudioSource {
        override var isMuted = false
        override val audioFormat = PcmAudioFormat(48000, 1)
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        private val pcm = ByteBuffer.allocateDirect(960)

        override suspend fun open(mixer: MediaMixer) = Result.success(Unit)

        override suspend fun close() = Result.success(Unit).also { closes.incrementAndGet() }

        override fun read(track: Int): MediaBuffer {
            Thread.sleep(10)
            reads.incrementAndGet()
            pcm.clear()
            return MediaBuffer(MediaType.AUDIO, track, pcm)
        }
    }
}
