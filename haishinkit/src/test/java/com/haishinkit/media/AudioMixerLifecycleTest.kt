package com.haishinkit.media

import com.haishinkit.media.source.AudioSource
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
