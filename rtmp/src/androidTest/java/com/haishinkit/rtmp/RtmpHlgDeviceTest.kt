package com.haishinkit.rtmp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.stream.Stream
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in local Enhanced RTMP round trip, with hlgRtmpUrl pointing to a test server. */
@RunWith(AndroidJUnit4::class)
class RtmpHlgDeviceTest {
    @Test fun hevcAndAv1HlgRoundTripThroughTheMixer() {
        val url = InstrumentationRegistry.getArguments().getString("hlgRtmpUrl")
        assumeNotNull(url)
        val external = InstrumentationRegistry.getArguments().getString("hlgExternalStream")
        val profiles = if (external != null) listOf(VideoCodecProfileLevel.AV1_MAIN10_4_1) else listOf(VideoCodecProfileLevel.HEVC_MAIN10_4_1, VideoCodecProfileLevel.AV1_MAIN10_4_1)
        for (profile in profiles) {
            val context = InstrumentationRegistry.getInstrumentation().context
            val mixer = MediaMixer(context, dynamicRangeMode = DynamicRangeMode.HLG)
            mixer.screen.frame = Rect(0, 0, 320, 180)
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            mixer.screen.addChild(
                ImageScreenObject().apply {
                    this.bitmap = bitmap
                    frame = Rect(0, 0, 320, 180)
                },
            )
            val source =
                object : MediaOutputDataSource by mixer {
                    override val hasVideo = true
                }
            val publishing = RtmpConnection()
            val playing = RtmpConnection()
            val publisher = RtmpStream(context, publishing)
            val player = RtmpStream(context, playing, DynamicRangeMode.HLG)
            val frames = AtomicInteger()
            val hlgFormat = AtomicInteger()
            try {
                publisher.dataSource = WeakReference(source)
                publisher.videoSetting.profileLevel = profile
                publisher.videoSetting.width = 320
                publisher.videoSetting.height = 180
                publisher.videoSetting.bitRate = 500_000
                val decoder = videoCodec(player)
                val muxer = player.muxer
                val receivingListener =
                    object : Codec.Listener by muxer {
                        override fun onFormatChanged(
                            mime: String,
                            format: MediaFormat,
                        ) {
                            if (mime == MediaFormat.MIMETYPE_VIDEO_RAW && format.containsKey("color-transfer")) {
                                if (format.getInteger("color-transfer") == MediaFormat.COLOR_TRANSFER_HLG && format.getInteger("color-standard") == MediaFormat.COLOR_STANDARD_BT2020) hlgFormat.incrementAndGet()
                            }
                            muxer.onFormatChanged(mime, format)
                        }

                        override fun onSampleOutput(
                            mime: String,
                            index: Int,
                            info: MediaCodec.BufferInfo,
                            buffer: ByteBuffer,
                        ): Boolean {
                            if (mime == MediaFormat.MIMETYPE_VIDEO_RAW) frames.incrementAndGet()
                            return muxer.onSampleOutput(mime, index, info, buffer)
                        }
                    }
                val path = external ?: "hlg-${profile.mime.substringAfter('/')}"
                if (external == null) {
                    publishing.connect(url!!)
                    waitUntil { publisher.readyState == RtmpStream.ReadyState.OPEN }
                    publisher.publish(path)
                    waitUntil { publisher.readyState == RtmpStream.ReadyState.PUBLISHING }
                    waitUntil { publisher.frameCount.get() >= 5 }
                    // MediaMTX probes the first video GOP before exposing the path to readers.
                    SystemClock.sleep(3000)
                }
                playing.connect(url!!)
                waitUntil { player.readyState == RtmpStream.ReadyState.OPEN }
                player.play(path)
                waitUntil { decoder.isRunning.get() }
                decoder.listener = receivingListener
                waitUntil { frames.get() >= 10 }
                // Decoder output precedes MediaLink presentation and composition.
                var referenceWhite = FloatArray(4)
                waitUntil {
                    referenceWhite = readLinearCenter(player)
                    referenceWhite.take(3).any { it > 0.01f }
                }
                for (value in referenceWhite.take(3)) org.junit.Assert.assertEquals(0.203f, value, 0.035f)
                val actual = decoder.codec!!.outputFormat
                org.junit.Assert.assertEquals(MediaFormat.COLOR_TRANSFER_HLG, actual.getInteger("color-transfer"))
                org.junit.Assert.assertEquals(MediaFormat.COLOR_STANDARD_BT2020, actual.getInteger("color-standard"))
                android.util.Log.i("RtmpHlgDeviceTest", "HLG_ROUNDTRIP ${profile.mime} frames=${frames.get()} format=${hlgFormat.get()}")
            } finally {
                player.close()
                publisher.close()
                playing.close()
                publishing.close()
                playing.dispose()
                publishing.dispose()
                player.dispose()
                publisher.dispose()
                mixer.dispose()
                bitmap.recycle()
            }
        }
    }

    private fun readLinearCenter(stream: RtmpStream): FloatArray {
        val screen = stream.screen
        val handler = screen.javaClass.getDeclaredMethod("getHandler").apply { isAccessible = true }.invoke(screen) as android.os.Handler
        val read =
            java.util.concurrent.FutureTask {
                val framebuffer = IntArray(1)
                android.opengl.GLES20.glGenFramebuffers(1, framebuffer, 0)
                try {
                    android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, framebuffer[0])
                    android.opengl.GLES20.glFramebufferTexture2D(android.opengl.GLES20.GL_FRAMEBUFFER, android.opengl.GLES20.GL_COLOR_ATTACHMENT0, android.opengl.GLES20.GL_TEXTURE_2D, screen.textureId, 0)
                    val buffer = ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                    android.opengl.GLES20.glReadPixels(screen.frame.width() / 2, screen.frame.height() / 2, 1, 1, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_FLOAT, buffer)
                    org.junit.Assert.assertEquals(android.opengl.GLES20.GL_NO_ERROR, android.opengl.GLES20.glGetError())
                    FloatArray(4) { buffer[it] }
                } finally {
                    android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0)
                    android.opengl.GLES20.glDeleteFramebuffers(1, framebuffer, 0)
                }
            }
        handler.post(read)
        return read.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun videoCodec(stream: RtmpStream) = Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(stream) as VideoCodec

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("HLG RTMP operation timed out", condition())
    }
}
