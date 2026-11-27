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
import org.junit.Assert.assertTrue
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

    @Test
    fun headerOnlySequenceStartRevokesAcceptedConfiguration() {
        withStream { stream, codec, _ ->
            codec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_AV1
            codec.isRunning.set(true)
            RtmpStream::class.java.getDeclaredField("videoConfigurationAccepted").apply { isAccessible = true }.setBoolean(stream, true)
            stream.connection.streams[1] = stream
            try {
                assertTrue(stream.canReceiveAv1)
                val wire = ByteBuffer.wrap(byteArrayOf(0x90.toByte(), 0x61, 0x76, 0x30, 0x31))
                val header =
                    RtmpVideoMessage().apply {
                        length = wire.remaining()
                        streamID = 1
                    }
                header.decode(wire)
                header.execute(stream.connection)
                assertFalse(stream.canReceiveAv1)
            } finally {
                codec.isRunning.set(false)
            }
        }
    }

    @Test fun mediaMtxCodedFramesXPreservesAv1PayloadAndTimestamp() {
        ShadowMediaCodecList.reset()
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AV1, 320, 180)).setIsEncoder(false).setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)).setProfileLevels(emptyArray()).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.av1.decoder").setIsEncoder(false).setCapabilities(capabilities).build())
        try {
            withStream { stream, codec, _ ->
                codec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_AV1
                codec.isRunning.set(true)
                stream.connection.streams[1] = stream
                RtmpStream::class.java.getDeclaredField("videoConfigurationAccepted").apply { isAccessible = true }.setBoolean(stream, true)
                val wire = byteArrayOf(0x93.toByte(), 0x61, 0x76, 0x30, 0x31, 0x12, 0, 0x32, 1, 0)
                val input =
                    RtmpVideoMessage().apply {
                        length = wire.size
                        streamID = 1
                        timestamp = 42
                    }
                input.decode(ByteBuffer.wrap(wire)).execute(stream.connection)
                val controller = RtmpMuxer::class.java.getDeclaredMethod("getVideoBufferController").apply { isAccessible = true }.invoke(stream.muxer)
                val queue = BufferController::class.java.getDeclaredField("messages").apply { isAccessible = true }.get(controller) as LinkedBlockingDeque<*>
                assertEquals(1, queue.size)
                val received = queue.first as RtmpVideoMessage
                assertEquals(ByteBuffer.wrap(wire.copyOfRange(5, wire.size)), received.data)
                assertEquals(42, received.timestamp)
                assertEquals(0, received.compositeTime)
                codec.isRunning.set(false)
            }
        } finally {
            ShadowMediaCodecList.reset()
        }
    }

    @Test fun hlgHevcUsesVisibleDecoderCropWithoutReattachingTheSurface() {
        withStream { stream, codec, screen ->
            codec.dynamicRangeMode = com.haishinkit.graphics.DynamicRangeMode.HLG
            codec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_HEVC
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_RAW, 320, 184).apply {
                    setInteger("crop-left", 0)
                    setInteger("crop-top", 0)
                    setInteger("crop-right", 319)
                    setInteger("crop-bottom", 179)
                }
            stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_VIDEO_RAW, format)
            assertEquals(Rect(0, 0, 320, 180), screen.frame)
            assertEquals(0, screen.attachments)
        }
    }

    @Test fun hlgOutputRejectsExplicitIncompatibleColorButAllowsAbsentOptionalKeys() {
        withStream { stream, codec, screen ->
            codec.dynamicRangeMode = com.haishinkit.graphics.DynamicRangeMode.HLG
            codec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_HEVC
            codec.isRunning.set(true)
            RtmpStream::class.java.getDeclaredField("videoConfigurationAccepted").apply { isAccessible = true }.setBoolean(stream, true)
            assertTrue(stream.acceptHlgOutputFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_RAW, 320, 180)))
            assertTrue(stream.canRenderVideo)
            val fullRange =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_RAW, 320, 180).apply {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                }
            stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_VIDEO_RAW, fullRange)
            assertFalse(stream.canRenderVideo)
            assertTrue(stream.muxer.onSampleOutput(MediaFormat.MIMETYPE_VIDEO_RAW, 0, android.media.MediaCodec.BufferInfo(), ByteBuffer.allocate(0)))
            fullRange.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            assertFalse(stream.acceptHlgOutputFormat(fullRange)) // Rejection is latched until close/restart.
            assertEquals(0, screen.attachments)
            codec.isRunning.set(false)
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
