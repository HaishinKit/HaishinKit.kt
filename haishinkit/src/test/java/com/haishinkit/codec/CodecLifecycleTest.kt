package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CodecLifecycleTest {
    @Test
    fun waitingInputListenerDoesNotBlockStopAndCannotTouchRetiredCodec() {
        val native = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val owner = TestCodec(native)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        owner.isRunning.set(true)
        owner.listener =
            object : Codec.Listener {
                override fun onInputBufferAvailable(
                    mime: String,
                    codec: MediaCodec,
                    index: Int,
                ) {
                    entered.countDown()
                    assertTrue(resume.await(3, TimeUnit.SECONDS))
                    assertFalse(owner.withCurrentCodec(codec) { error("Retired decoder was accessed") })
                }

                override fun onFormatChanged(
                    mime: String,
                    mediaFormat: MediaFormat,
                ) {}

                override fun onSampleOutput(
                    mime: String,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                    buffer: ByteBuffer,
                ): Boolean = true
            }
        try {
            val callback = threads.submit { owner.onInputBufferAvailable(native, 0) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            threads.submit { owner.stopRunning() }.get(3, TimeUnit.SECONDS)
            assertFalse(owner.isRunning.get())
            resume.countDown()
            callback.get(3, TimeUnit.SECONDS)
        } finally {
            resume.countDown()
            threads.shutdownNow()
            native.release()
        }
    }

    @Test
    fun outputFormatNotificationCanWaitForSurfaceWorkerWithoutHoldingCodecMonitor() {
        val native = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val owner = TestCodec(native)
        val worker = Executors.newSingleThreadExecutor()
        var notified = false
        owner.isRunning.set(true)
        owner.listener =
            object : EmptyListener() {
                override fun onFormatChanged(
                    mime: String,
                    mediaFormat: MediaFormat,
                ) {
                    assertFalse(Thread.holdsLock(owner))
                    worker.submit { owner.surface = null }.get(2, TimeUnit.SECONDS)
                    notified = true
                }
            }
        try {
            owner.onOutputFormatChanged(native, MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 44100, 1))
            assertTrue(notified)
        } finally {
            owner.dispose()
            worker.shutdownNow()
            native.release()
        }
    }

    @Test
    fun shutdownWaitsForProducerDetachmentWithoutOwningCodecMonitor() {
        val native = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val worker = Executors.newSingleThreadExecutor()
        val owner = WorkerCodec(native, worker)
        owner.isRunning.set(true)
        try {
            owner.stopRunning()
            assertTrue(owner.detached)
            assertFalse(owner.isRunning.get())
        } finally {
            worker.shutdownNow()
            native.release()
        }
    }

    @Test
    fun startupConfigurationCanWaitForSurfaceWorkerWithoutOwningCodecMonitor() {
        val native = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val worker = Executors.newSingleThreadExecutor()
        val owner = WorkerCodec(native, worker)
        val rejected = IllegalStateException("Test ends after configuration's worker interaction")
        owner.configureAction = {
            assertFalse(Thread.holdsLock(owner))
            worker.submit { owner.surface = null }.get(2, TimeUnit.SECONDS)
            throw rejected
        }
        try {
            assertSame(rejected, assertThrows(IllegalStateException::class.java) { owner.startRunning() })
        } finally {
            owner.dispose()
            worker.shutdownNow()
            native.release()
        }
    }

    private open class EmptyListener : Codec.Listener {
        override fun onInputBufferAvailable(
            mime: String,
            codec: MediaCodec,
            index: Int,
        ) {}

        override fun onFormatChanged(
            mime: String,
            mediaFormat: MediaFormat,
        ) {}

        override fun onSampleOutput(
            mime: String,
            index: Int,
            info: MediaCodec.BufferInfo,
            buffer: ByteBuffer,
        ) = true
    }

    private class WorkerCodec(native: MediaCodec, private val worker: ExecutorService) : Codec() {
        override var codec: MediaCodec? = native
            set(value) {
                if (value == null && field != null) {
                    assertFalse(Thread.holdsLock(this))
                    worker.submit { surface = null }.get(2, TimeUnit.SECONDS)
                    detached = true
                }
                field = value
            }
        override var inputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
        override var outputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW
        var detached = false
        var configureAction: () -> Unit = {}

        override fun configure(codec: MediaCodec) = configureAction()

        override fun createMediaFormat(mime: String): MediaFormat = MediaFormat.createAudioFormat(mime, 44100, 1)
    }

    private class TestCodec(native: MediaCodec) : Codec() {
        override var codec: MediaCodec? = native
        override var inputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
        override var outputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW

        override fun createMediaFormat(mime: String): MediaFormat = MediaFormat.createAudioFormat(mime, 44100, 1)
    }
}
