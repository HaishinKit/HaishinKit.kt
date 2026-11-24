package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.media.BufferController
import com.haishinkit.media.source.VideoSource
import com.haishinkit.rtmp.message.RtmpVideoMessage
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.stream.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class Av1PlaybackTest {
    @Test
    fun outputDimensionsUseVisibleCropWithoutReattachingSurface() {
        withStream { stream, codec, screen ->
            codec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_AV1
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_RAW, 1920, 1088).apply {
                    setInteger("crop-left", 0)
                    setInteger("crop-top", 0)
                    setInteger("crop-right", 1919)
                    setInteger("crop-bottom", 1079)
                }
            stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_VIDEO_RAW, format)
            assertEquals(Rect(0, 0, 1920, 1080), screen.frame)
            assertEquals(0, screen.attachments)
        }
    }

    @Test
    fun missingOrRejectedInitialHeaderDoesNotBufferAv1Frames() {
        ShadowMediaCodecList.reset()
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AV1, 320, 180))
                .setIsEncoder(false)
                .setProfileLevels(emptyArray())
                .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                .build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("test.av1.decoder").setIsEncoder(false)
                .setCapabilities(capabilities).build(),
        )
        try {
            withStream { stream, _, _ ->
                val connection = stream.connection
                connection.streams[1] = stream
                for (rejectedHeader in listOf(false, true)) {
                    if (rejectedHeader) {
                        message(0, byteArrayOf(0x01)).execute(connection)
                    }
                    repeat(10) { message(1, byteArrayOf(0x32, 0)).execute(connection) }
                    val controller =
                        RtmpMuxer::class.java.getDeclaredMethod("getVideoBufferController").apply {
                            isAccessible = true
                        }.invoke(stream.muxer)
                    val queue =
                        BufferController::class.java.getDeclaredField("messages").apply {
                            isAccessible = true
                        }.get(controller) as LinkedBlockingDeque<*>
                    assertEquals(0, queue.size)
                    assertFalse(stream.hasVideo)
                }
            }
        } finally {
            ShadowMediaCodecList.reset()
        }
    }

    private fun message(
        packet: Int,
        bytes: ByteArray,
    ): RtmpVideoMessage =
        RtmpVideoMessage().apply {
            isExHeader = true
            fourCC = RtmpMuxer.FLV_VIDEO_FOUR_CC_AV01
            packetType = packet.toByte()
            frame = RtmpMuxer.FLV_FRAME_TYPE_KEY
            streamID = 1
            data = ByteBuffer.wrap(bytes)
        }

    private fun withStream(action: (RtmpStream, VideoCodec, TestScreen) -> Unit) {
        val factory = Screen.factory
        val screen = TestScreen(RuntimeEnvironment.getApplication())
        try {
            Screen.factory = { screen }
            val stream = RtmpStream(RuntimeEnvironment.getApplication(), RtmpConnection())
            val codec = Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(stream) as VideoCodec
            Stream::class.java.getDeclaredMethod("setMode", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(stream, Codec.MODE_DECODE)
            action(stream, codec, screen)
        } finally {
            Screen.factory = factory
        }
    }

    private class TestScreen(context: Context) : Screen(context) {
        var attachments = 0

        override fun readPixels(lambda: (Bitmap?) -> Unit) {}

        override fun bind(screenObject: ScreenObject) {}

        override fun unbind(screenObject: ScreenObject) {}

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) {
            attachments++
        }
    }
}
