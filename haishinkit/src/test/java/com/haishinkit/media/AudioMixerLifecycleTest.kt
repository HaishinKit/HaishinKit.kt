package com.haishinkit.media

import com.haishinkit.media.audio.AudioMixer
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.PcmAudioSource
import com.haishinkit.screen.AssetScreenObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AudioMixerLifecycleTest {
    @Test fun invalidInputFormatIsClosedBeforeItCanDisruptHealthyInputs() =
        runBlocking {
            val owner = MediaMixer(RuntimeEnvironment.getApplication())
            val mixer = AudioMixer(owner) {}
            val closed = AtomicInteger()
            val healthy =
                object : AudioSource {
                    override var isMuted = false

                    override suspend fun open(mixer: MediaMixer) = Result.success(Unit)

                    override suspend fun close() = Result.success(Unit)

                    override fun read(track: Int) = MediaBuffer(MediaType.AUDIO, track, ByteBuffer.allocateDirect(0))
                }
            val unsupported =
                object : PcmAudioSource, AudioSource by healthy {
                    override val audioFormat: PcmAudioFormat get() = PcmAudioFormat(48000, 6)

                    override suspend fun close() = Result.success(Unit).also { closed.incrementAndGet() }
                }
            try {
                mixer.attach(0, healthy).getOrThrow()
                assertTrue(mixer.attach(1, unsupported).isFailure)
                assertEquals(1, closed.get())
                assertTrue(mixer.hasAudio)
                mixer.attach(0, null).getOrThrow()
                assertFalse(mixer.hasAudio)
            } finally {
                mixer.stop(dispose = true)
                owner.dispose()
            }
        }

    @Test fun trackLimitAndReopenFailuresReturnResultsAndReleasePartialInputs() =
        runBlocking {
            val owner = MediaMixer(RuntimeEnvironment.getApplication())
            val mixer = AudioMixer(owner) {}
            var fail = false
            val closed = AtomicInteger()

            fun source() =
                object : AudioSource {
                    override var isMuted = false

                    override suspend fun open(mixer: MediaMixer): Result<Unit> = if (fail) Result.failure(IllegalStateException("Unavailable")) else Result.success(Unit)

                    override suspend fun close() = Result.success(Unit).also { closed.incrementAndGet() }

                    override fun read(track: Int) = MediaBuffer(MediaType.AUDIO, track, ByteBuffer.allocateDirect(0))
                }
            val first = source()
            try {
                mixer.attach(0, first).getOrThrow()
                for (track in 1..31) mixer.attach(track, source()).getOrThrow()
                assertTrue(mixer.attach(32, source()).isFailure)
                mixer.stop()
                assertEquals(32, closed.get())
                fail = true
                assertTrue(mixer.attach(0, first).isFailure)
                assertEquals(33, closed.get())
                for (track in 1..31) mixer.attach(track, null).getOrThrow()
                assertFalse(mixer.hasAudio)
            } finally {
                mixer.stop(dispose = true)
                owner.dispose()
            }
        }

    @Test fun closingAnAssetBeforeCaptureStartsRetiresItsAudioTrack() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val mixer = MediaMixer(context)
            try {
                repeat(40) {
                    val asset = AssetScreenObject(context)
                    mixer.attachAudio(asset.audioTrack, asset.audioSource).getOrThrow()
                    assertTrue(mixer.hasAudio)
                    asset.close()
                    assertFalse(mixer.hasAudio)
                    assertTrue(mixer.attachAudio(asset.audioTrack, asset.audioSource).isFailure)
                }
            } finally {
                mixer.dispose()
            }
        }

    @Test fun cancellationDuringOpeningClosesThePartlyOpenedInput() =
        runBlocking {
            val mixer = MediaMixer(RuntimeEnvironment.getApplication())
            val opening = CompletableDeferred<Unit>()
            val closes = AtomicInteger()
            val source =
                object : AudioSource {
                    override var isMuted = false

                    override suspend fun open(mixer: MediaMixer): Result<Unit> {
                        opening.complete(Unit)
                        awaitCancellation()
                    }

                    override suspend fun close(): Result<Unit> = Result.success(Unit).also { closes.incrementAndGet() }

                    override fun read(track: Int) = MediaBuffer(MediaType.AUDIO, track, ByteBuffer.allocateDirect(0))
                }
            try {
                val attaching = launch { mixer.attachAudio(0, source) }
                opening.await()
                attaching.cancelAndJoin()
                assertEquals(1, closes.get())
                assertFalse(mixer.hasAudio)
            } finally {
                mixer.dispose()
            }
        }
}
