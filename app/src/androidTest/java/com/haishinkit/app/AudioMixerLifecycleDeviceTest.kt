package com.haishinkit.app

import android.graphics.Rect
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.media.source.PcmAudioSource
import com.haishinkit.media.source.VideoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AudioMixerLifecycleDeviceTest {
    @Test fun audioStopsWhileNewVideoIsOpening() = blockedVideoLifecycle(false)

    @Test fun audioDisposesWhileNewVideoIsOpening() = blockedVideoLifecycle(true)

    @Test fun audioStartsBeforeTheInitialVideoOpenCompletes() =
        runBlocking {
            val mixer = newMixer()
            val audio = AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 1000)
            val video = GatedVideo(blockOpen = true)
            val output = Probe()
            try {
                mixer.attachAudio(0, audio).getOrThrow()
                mixer.attachVideo(0, video).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                withTimeout(3000) { video.opened.await() }
                await("Audio start waited for video") { output.value.get() == 1000 }
            } finally {
                video.openRelease.complete(Unit)
                mixer.stopRunning()
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
        }

    @Test fun audioStopDoesNotWaitForVideoClose() =
        runBlocking {
            val mixer = newMixer()
            val audio = AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 1000)
            val video = GatedVideo(blockClose = true)
            val output = Probe()
            try {
                mixer.attachAudio(0, audio).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                await("Audio did not start") { output.value.get() == 1000 }
                mixer.attachVideo(0, video).getOrThrow()
                mixer.stopRunning()
                withTimeout(3000) { video.closing.await() }
                await("Audio stop waited for video close") { audio.closes.get() > 0 }
                assertStopped(output)
            } finally {
                video.closeRelease.complete(Unit)
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
        }

    @Test fun failedReadCanBeReattachedWithoutStoppingHealthyInput() =
        runBlocking {
            val mixer = newMixer()
            val healthy = AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 1000)
            val input = AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 2000)
            val failed = AtomicBoolean()
            val flaky =
                object : PcmAudioSource by input {
                    override fun read(track: Int): MediaBuffer {
                        if (!failed.getAndSet(true)) throw IOException("Injected capture failure")
                        return input.read(track)
                    }
                }
            val output = Probe()
            try {
                mixer.attachAudio(0, healthy).getOrThrow()
                mixer.attachAudio(1, flaky).getOrThrow()
                mixer.registerOutput(output)
                mixer.startRunning()
                await("Failed input was not released") { input.closes.get() > 0 && output.value.get() == 1000 }
                assertEquals(0, healthy.closes.get())
                mixer.attachAudio(1, flaky).getOrThrow()
                await("Same-source retry did not recover") { input.opens.get() >= 2 && output.value.get() == 3000 }
            } finally {
                mixer.stopRunning()
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
        }

    private fun blockedVideoLifecycle(dispose: Boolean) =
        runBlocking {
            val mixer = newMixer()
            val audio = AudioCompositionTest.ConstantSource(PcmAudioFormat(48000, 1), 1000)
            val video = GatedVideo(blockOpen = true)
            val output = Probe()
            mixer.attachAudio(0, audio).getOrThrow()
            mixer.registerOutput(output)
            mixer.startRunning()
            await("Audio did not start") { output.value.get() == 1000 }
            val attachment = async(Dispatchers.IO) { mixer.attachVideo(0, video) }
            try {
                withTimeout(3000) { video.opened.await() }
                if (dispose) mixer.dispose() else mixer.stopRunning()
                await("Audio teardown waited for video open") { audio.closes.get() > 0 }
                assertStopped(output)
            } finally {
                video.openRelease.complete(Unit)
                withTimeout(3000) { attachment.await() }
                mixer.unregisterOutput(output)
                mixer.dispose()
            }
        }

    private fun newMixer() = MediaMixer(InstrumentationRegistry.getInstrumentation().targetContext).apply { screen.frame = Rect(0, 0, 64, 48) }

    private fun assertStopped(output: Probe) {
        val count = output.count.get()
        SystemClock.sleep(60)
        assertEquals("PCM continued after input release", count, output.count.get())
    }

    private fun await(
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

    private class Probe : MediaOutput {
        override var dataSource: WeakReference<MediaOutputDataSource>? = null
        val value = AtomicInteger(Int.MIN_VALUE)
        val count = AtomicInteger()

        override fun append(buffer: MediaBuffer) {
            value.set(checkNotNull(buffer.payload).getShort(200).toInt())
            count.incrementAndGet()
        }
    }

    private class GatedVideo(blockOpen: Boolean = false, blockClose: Boolean = false) : VideoSource {
        override var surface: Surface? = null
        override val videoSize = Size(64, 48)
        override val imageOrientation = ImageOrientation.UP
        val opened = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val openRelease = CompletableDeferred<Unit>()
        val closeRelease = CompletableDeferred<Unit>()

        init {
            if (!blockOpen) openRelease.complete(Unit)
            if (!blockClose) closeRelease.complete(Unit)
        }

        override suspend fun open(mixer: MediaMixer): Result<Unit> {
            opened.complete(Unit)
            openRelease.await()
            return Result.success(Unit)
        }

        override suspend fun close(): Result<Unit> {
            closing.complete(Unit)
            closeRelease.await()
            return Result.success(Unit)
        }
    }
}
