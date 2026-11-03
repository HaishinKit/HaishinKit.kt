package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
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

    private class TestCodec(native: MediaCodec) : Codec() {
        override var codec: MediaCodec? = native
        override var inputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
        override var outputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW

        override fun createMediaFormat(mime: String): MediaFormat = MediaFormat.createAudioFormat(mime, 44100, 1)
    }
}
