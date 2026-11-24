package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.BufferController
import com.haishinkit.media.source.VideoSource
import com.haishinkit.rtmp.iso.VpDecoderConfigurationRecord
import com.haishinkit.rtmp.message.RtmpVideoMessage
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.stream.Stream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class VpPlaybackInitializationTest {
    private val originalScreenFactory = Screen.factory
    private val originalTransformFactory = PixelTransform.factory
    private val context: Context = RuntimeEnvironment.getApplication()
    private val screen = TestScreen(context)
    private val stream by lazy { RtmpStream(context, RtmpConnection()) }
    private val codec: VideoCodec
        get() = Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(stream) as VideoCodec

    @Before
    fun setUp() {
        Screen.factory = { screen }
        PixelTransform.factory = { TestTransform(it) }
        ShadowMediaCodecList.reset()
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9)) {
            val caps =
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat.createVideoFormat(mime, 1280, 720)).setIsEncoder(false)
                    .setProfileLevels(
                        arrayOf(
                            MediaCodecInfo.CodecProfileLevel().apply {
                                profile = 1
                                level = if (mime == MediaFormat.MIMETYPE_VIDEO_VP9) MediaCodecInfo.CodecProfileLevel.VP9Level31 else MediaCodecInfo.CodecProfileLevel.VP8Level_Version0
                            },
                        ),
                    ).setColorFormats(intArrayOf(19)).build()
            if (mime == MediaFormat.MIMETYPE_VIDEO_VP9) {
                val otherCaps =
                    MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createVideoFormat(mime, 1280, 720)).setIsEncoder(false)
                        .setProfileLevels(
                            arrayOf(
                                MediaCodecInfo.CodecProfileLevel().apply {
                                    profile = MediaCodecInfo.CodecProfileLevel.VP9Profile2
                                    level = MediaCodecInfo.CodecProfileLevel.VP9Level31
                                },
                            ),
                        ).setColorFormats(intArrayOf(19)).build()
                ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("incompatible.$mime.decoder").setIsEncoder(false).setCapabilities(otherCaps).build())
            }
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.$mime.decoder").setIsEncoder(false).setCapabilities(caps).build())
        }
    }

    @After
    fun tearDown() {
        stream.dispose()
        ShadowMediaCodec.clearCodecs()
        ShadowMediaCodecList.reset()
        Screen.factory = originalScreenFactory
        PixelTransform.factory = originalTransformFactory
    }

    @Test
    fun vp8WaitsForSurfaceAndQueuesOnlyRawCodedData() = checkStartup(MediaFormat.MIMETYPE_VIDEO_VP8, "vp8")

    @Test
    fun vp9WaitsForSurfaceAndQueuesOnlyRawCodedData() = checkStartup(MediaFormat.MIMETYPE_VIDEO_VP9, "vp9")

    @Test
    fun decoderAllocationFailureDoesNotEscapePlayback() = checkStartup(MediaFormat.MIMETYPE_VIDEO_VP9, "vp9", true)

    @Test
    fun missingSurfaceDoesNotRetryOnEveryKeyframe() = checkStartup(MediaFormat.MIMETYPE_VIDEO_VP9, "vp9", surfaceFailure = true)

    @Test
    fun unsupportedProfileDoesNotAttachRendererOrReplaceOptions() {
        val mime = MediaFormat.MIMETYPE_VIDEO_VP9
        ShadowMediaCodecList.reset()
        val caps =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(mime, 1280, 720)).setIsEncoder(false)
                .setProfileLevels(
                    arrayOf(
                        MediaCodecInfo.CodecProfileLevel().apply {
                            profile = MediaCodecInfo.CodecProfileLevel.VP9Profile0
                            level = MediaCodecInfo.CodecProfileLevel.VP9Level31
                        },
                    ),
                ).setColorFormats(intArrayOf(19)).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("profile0.only").setIsEncoder(false).setCapabilities(caps).build())
        stream.readyState = RtmpStream.ReadyState.PLAY
        val originalOptions = listOf(com.haishinkit.codec.CodecOption("vendor-option", 7))
        stream.videoSetting.options = originalOptions
        val frame = keyframe("vp9-profile2")
        val record = VpDecoderConfigurationRecord.fromKeyFrame(mime, frame)
        assertTrue(stream.configure(VpDecoderConfigurationRecord.decode(mime, record.toByteBuffer())))
        assertFalse(stream.prepareVpFrame(mime, frame))
        assertTrue(screen.sources.isEmpty())
        assertEquals(originalOptions, codec.options)
        assertFalse(codec.isRunning.get())
    }

    @Test
    fun closeWhileWaitingForSurfaceCannotStartVpDecoder() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        val executor = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            val pending = executor.submit<Boolean> { stream.configure(record(MediaFormat.MIMETYPE_VIDEO_VP9).withKeyFrameSize(keyframe("vp9"))) }
            val source = screen.sources.poll(2, TimeUnit.SECONDS)!!
            stream.close()
            source.surface = surface
            assertFalse(pending.get(2, TimeUnit.SECONDS))
            assertFalse(codec.isRunning.get())
            assertFalse(stream.canReceiveCodedVideo)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            surface.release()
            texture.release()
        }
    }

    private fun checkStartup(
        mime: String,
        name: String,
        allocationFailure: Boolean = false,
        surfaceFailure: Boolean = false,
    ) {
        stream.readyState = RtmpStream.ReadyState.PLAY
        stream.connection.streams[1] = stream
        codec.listener = null
        val configuredSurface = AtomicReference<Surface>()
        val consumed = AtomicReference<ByteArray>()
        var configurations = 0
        ShadowMediaCodec.addDecoder(
            "test.$mime.decoder",
            ShadowMediaCodec.CodecConfig(
                16384,
                16384,
                object : ShadowMediaCodec.CodecConfig.Codec {
                    override fun onConfigured(
                        format: MediaFormat,
                        surface: Surface?,
                        crypto: MediaCrypto?,
                        flags: Int,
                    ) {
                        if (allocationFailure) throw java.io.IOException("decoder allocation failed")
                        assertTrue(Thread.holdsLock(stream))
                        assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH))
                        assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
                        assertFalse(format.containsKey("csd-0"))
                        assertFalse(format.containsKey("csd-1"))
                        configuredSurface.set(surface)
                        configurations++
                        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_RAW)
                    }

                    override fun process(
                        input: ByteBuffer,
                        output: ByteBuffer,
                    ) {
                        consumed.set(ByteArray(input.remaining()).also { input.get(it) })
                    }
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            assertTrue(stream.configure(record(mime)))
            assertFalse(codec.isRunning.get())
            assertFalse(stream.prepareVpFrame(mime, ByteBuffer.wrap(byteArrayOf(1))))
            val pending = executor.submit<Boolean> { stream.prepareVpFrame(mime, keyframe(name)) }
            val source = screen.sources.poll(2, TimeUnit.SECONDS)!!
            assertFalse(pending.isDone)
            assertNull(configuredSurface.get())
            if (surfaceFailure) {
                assertFalse(pending.get(7, TimeUnit.SECONDS))
                assertFalse(stream.prepareVpFrame(mime, keyframe(name)))
                assertTrue(screen.sources.isEmpty())
                assertFalse(codec.isRunning.get())
                return
            }
            source.surface = surface
            if (allocationFailure) {
                assertFalse(pending.get(2, TimeUnit.SECONDS))
                assertFalse(stream.canReceiveCodedVideo)
                assertFalse(codec.isRunning.get())
                assertFalse(stream.prepareVpFrame(mime, keyframe(name)))
                assertNull(Codec::class.java.getDeclaredField("backgroundHandler").apply { isAccessible = true }.get(codec))
                return
            }
            assertTrue(pending.get(2, TimeUnit.SECONDS))
            assertSame(surface, configuredSurface.get())
            assertTrue(stream.canReceiveCodedVideo)
            assertFalse(stream.canReceiveAv1)
            assertTrue(stream.configure(record(mime)))
            assertEquals(1, configurations)
            // An attempted switch must leave the current valid configuration usable.
            val other = if (mime == MediaFormat.MIMETYPE_VIDEO_VP8) MediaFormat.MIMETYPE_VIDEO_VP9 else MediaFormat.MIMETYPE_VIDEO_VP8
            message(other, RtmpMuxer.FLV_VIDEO_PACKET_TYPE_SEQUENCE_START, record(other).toByteBuffer().array()).execute(stream.connection)
            assertTrue(stream.canReceiveCodedVideo)
            assertEquals(mime, stream.inputVideoMimeType)
            message(mime, RtmpMuxer.FLV_VIDEO_PACKET_TYPE_SEQUENCE_START, byteArrayOf(0, 0, 0x82.toByte(), 2, 2, 2, 0, 0)).execute(stream.connection)
            assertTrue(stream.canReceiveCodedVideo)
            assertEquals(1, configurations)
            message(mime, RtmpMuxer.FLV_VIDEO_PACKET_TYPE_CODED_FRAMES_X, byteArrayOf(1, 2, 3)).execute(stream.connection)
            assertEquals(0, queuedFrames())
            val bytes = javaClass.getResourceAsStream("/vpx/$name-keyframe.bin")!!.use { it.readBytes() }
            message(mime, RtmpMuxer.FLV_VIDEO_PACKET_TYPE_CODED_FRAMES, bytes).execute(stream.connection)
            executor.submit { stream.muxer.onInputBufferAvailable(MediaFormat.MIMETYPE_VIDEO_RAW, codec.codec!!, 0) }.get(2, TimeUnit.SECONDS)
            assertArrayEquals(bytes, consumed.get())
            message(mime, RtmpMuxer.FLV_VIDEO_PACKET_TYPE_SEQUENCE_START, byteArrayOf(1)).execute(stream.connection)
            assertFalse(stream.canReceiveCodedVideo)
            message(mime, RtmpMuxer.FLV_VIDEO_PACKET_TYPE_CODED_FRAMES, bytes).execute(stream.connection)
            assertEquals(0, queuedFrames())
            assertTrue(stream.configure(record(mime)))
            stream.close()
            assertEquals(stream.videoSetting.options, codec.options)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            surface.release()
            texture.release()
        }
    }

    private fun queuedFrames(): Int {
        val controller = RtmpMuxer::class.java.getDeclaredMethod("getVideoBufferController").apply { isAccessible = true }.invoke(stream.muxer)
        val queue = BufferController::class.java.getDeclaredField("messages").apply { isAccessible = true }.get(controller) as java.util.concurrent.BlockingQueue<*>
        return queue.size
    }

    private fun keyframe(name: String) = ByteBuffer.wrap(javaClass.getResourceAsStream("/vpx/$name-keyframe.bin")!!.use { it.readBytes() })

    private fun record(mime: String) = VpDecoderConfigurationRecord.decode(mime, ByteBuffer.wrap(byteArrayOf(0, 0, 0x82.toByte(), 2, 2, 2, 0, 0)))

    private fun message(
        mime: String,
        packet: Byte,
        bytes: ByteArray,
    ) =
        RtmpVideoMessage().apply {
            isExHeader = true
            fourCC = RtmpMuxer.getVideoFourCCByType(mime)
            packetType = packet
            frame = RtmpMuxer.FLV_FRAME_TYPE_KEY
            streamID = 1
            data = ByteBuffer.wrap(bytes)
        }

    private class TestScreen(context: Context) : Screen(context) {
        val sources = LinkedBlockingQueue<VideoSource>()

        override fun readPixels(lambda: (Bitmap?) -> Unit) {}

        override fun bind(screenObject: ScreenObject) {}

        override fun unbind(screenObject: ScreenObject) {}

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) {
            video?.let { sources.add(it) }
        }
    }

    private class TestTransform(override val context: Context) : PixelTransform {
        override var screen: Screen? = null
        override var surface: Surface? = null
        override var imageExtent = Size(0, 0)
        override var videoEffect: VideoEffect = DefaultVideoEffect.shared
        override var videoGravity = VideoGravity.RESIZE_ASPECT
        override var frameRate = 30
        override var backgroundColor = 0
    }
}
