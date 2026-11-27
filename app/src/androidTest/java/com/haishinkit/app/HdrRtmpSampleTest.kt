package com.haishinkit.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.codec.VideoCodec
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Opt in with hlgRtmpUrl; uses the sample's persisted selection and session/encoding configuration. */
@SdkSuppress(minSdkVersion = 33)
class HdrRtmpSampleTest {
    @Test fun bothSampleCodecSelectionsPublishAndReceiveHlg() =
        runBlocking {
            val url = InstrumentationRegistry.getArguments().getString("hlgRtmpUrl")
            assumeNotNull(url)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val previous = Preference.shared
            val previousHdr = HdrPreference.selected(context)
            val previousRenderer = RendererPreference.selected(context)
            try {
                HdrPreference.save(context, HdrSettings(DynamicRangeMode.HLG, DynamicRangeMode.HLG))
                RendererPreference.configure(context)
                StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
                for (codec in listOf(PublishVideoCodec.H265, PublishVideoCodec.AV1)) {
                    Preference.shared = Preference(url!!, "sample-hlg-${codec.name.lowercase()}", encoding = PublishEncodingSettings(videoCodec = codec, videoBitRate = 500_000))
                    val mixer = MediaMixer(context, HdrPreference.selected(context).publishing)
                    val publisher = Preference.shared.sessionSettings().create(context)
                    val player = Preference.shared.sessionSettings(StreamSession.Mode.PLAYBACK).create(context)
                    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    val source =
                        object : MediaOutputDataSource by mixer {
                            override val hasVideo = true
                        }
                    try {
                        mixer.screen.frame = Rect(0, 0, 320, 180)
                        mixer.screen.addChild(
                            ImageScreenObject().apply {
                                this.bitmap = bitmap
                                frame = Rect(0, 0, 320, 180)
                            },
                        )
                        publisher.stream.dataSource = WeakReference(source)
                        val encoding = Preference.shared.encoding.forDynamicRange(HdrPreference.selected(context).publishing)
                        // Check the app's normal 1280x720 settings before using a small network fixture.
                        encoding.applyTo(publisher.stream, PublishingProtocol.RTMP, audioEnabled = false)
                        publisher.stream.videoSetting.width = 320
                        publisher.stream.videoSetting.height = 180
                        encoding.applyTo(publisher.stream, PublishingProtocol.RTMP, audioEnabled = false)
                        assertEquals(2, publisher.stream.videoSetting.profileLevel.profile)
                        assertEquals(DynamicRangeMode.HLG, player.stream.screen.dynamicRangeMode)
                        mixer.startRunning()
                        publisher.connect().getOrThrow()
                        SystemClock.sleep(3000) // The server probes its first GOP before accepting readers.
                        player.connect().getOrThrow()
                        val deadline = SystemClock.elapsedRealtime() + 15_000
                        var pixel = FloatArray(4)
                        while (SystemClock.elapsedRealtime() < deadline) {
                            pixel = readPixel(player.stream.screen)
                            if (pixel[0] > 0.01f) break
                            SystemClock.sleep(30)
                        }
                        for (channel in 0..2) assertEquals("${codec.name} decoded white", 0.203f, pixel[channel], 0.035f)
                        val decoder = Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(player.stream) as VideoCodec
                        val actual = decoder.codec!!.outputFormat
                        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, actual.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, actual.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                        assertTrue(player.isConnected)
                    } finally {
                        player.close()
                        publisher.close()
                        player.stream.dispose()
                        publisher.stream.dispose()
                        mixer.dispose()
                        bitmap.recycle()
                    }
                }
            } finally {
                Preference.shared = previous
                HdrPreference.save(context, previousHdr)
                RendererPreference.save(context, previousRenderer)
                RendererPreference.configure(context)
            }
        }

    private fun readPixel(screen: Screen): FloatArray {
        val handler = screen.javaClass.getDeclaredMethod("getHandler").apply { isAccessible = true }.invoke(screen) as android.os.Handler
        val read =
            FutureTask {
                val framebuffer = IntArray(1)
                android.opengl.GLES20.glGenFramebuffers(1, framebuffer, 0)
                try {
                    android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, framebuffer[0])
                    android.opengl.GLES20.glFramebufferTexture2D(android.opengl.GLES20.GL_FRAMEBUFFER, android.opengl.GLES20.GL_COLOR_ATTACHMENT0, android.opengl.GLES20.GL_TEXTURE_2D, screen.textureId, 0)
                    val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    android.opengl.GLES20.glReadPixels(screen.frame.width() / 2, screen.frame.height() / 2, 1, 1, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_FLOAT, buffer)
                    assertEquals(android.opengl.GLES20.GL_NO_ERROR, android.opengl.GLES20.glGetError())
                    FloatArray(4) { buffer[it] }
                } finally {
                    android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0)
                    android.opengl.GLES20.glDeleteFramebuffers(1, framebuffer, 0)
                }
            }
        handler.post(read)
        return read.get(5, TimeUnit.SECONDS)
    }
}
