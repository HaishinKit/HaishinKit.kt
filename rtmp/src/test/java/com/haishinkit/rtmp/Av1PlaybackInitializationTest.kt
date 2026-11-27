package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.haishinkit.codec.Codec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.source.VideoSource
import com.haishinkit.rtmp.iso.Av1DecoderConfigurationRecord
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
class Av1PlaybackInitializationTest {
    private val originalScreenFactory = Screen.factory
    private val originalTransformFactory = PixelTransform.factory
    private val context: Context = RuntimeEnvironment.getApplication()
    private val screen = TestScreen(context)
    private val stream by lazy { RtmpStream(context, RtmpConnection()) }
    private val codec: VideoCodec
        get() = Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(stream) as VideoCodec
    private val fixture = "81000c000a0b0200000521e7f66017c002".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Before
    fun setUp() {
        Screen.factory = { screen }
        PixelTransform.factory = { TestTransform(it) }
        ShadowMediaCodecList.reset()
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AV1, 320, 180))
                .setIsEncoder(false).setProfileLevels(emptyArray()).setColorFormats(intArrayOf(19)).build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("test.av1.decoder").setIsEncoder(false).setCapabilities(capabilities).build(),
        )
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
    fun directPlayOnOpenStreamEntersDecodeMode() {
        stream.readyState = RtmpStream.ReadyState.OPEN
        stream.play("av1")
        assertEquals(RtmpStream.ReadyState.PLAY, stream.readyState)
        assertEquals(Codec.MODE_DECODE, codec.mode)
        assertFalse(stream.canReceiveAv1)
    }

    @Test
    fun delayedSurfacePrecedesDecoderConfigurationAndFirstFrameReachesInput() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        // Drive the platform input callback explicitly after queuing one frame.
        codec.listener = null
        val configuredSurface = AtomicReference<Surface>()
        val consumed = AtomicReference<ByteArray>()
        var configurations = 0
        ShadowMediaCodec.addDecoder(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            ShadowMediaCodec.CodecConfig(
                4096,
                4096,
                object : ShadowMediaCodec.CodecConfig.Codec {
                    override fun onConfigured(
                        format: MediaFormat,
                        surface: Surface?,
                        crypto: MediaCrypto?,
                        flags: Int,
                    ) {
                        assertTrue("Decoder startup must be serialized with stream close", Thread.holdsLock(stream))
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
            val configuring = executor.submit<Boolean> { stream.configure(record()) }
            val source = screen.sources.poll(2, TimeUnit.SECONDS)!!
            assertFalse(configuring.isDone)
            assertNull(configuredSurface.get())
            source.surface = surface
            assertTrue(configuring.get(2, TimeUnit.SECONDS))
            assertSame(surface, configuredSurface.get())
            assertTrue(stream.canReceiveAv1)

            val bytes = byteArrayOf(0x32, 2, 0, 1)
            stream.connection.streams[1] = stream
            RtmpVideoMessage().apply {
                isExHeader = true
                fourCC = RtmpMuxer.FLV_VIDEO_FOUR_CC_AV01
                packetType = RtmpMuxer.FLV_VIDEO_PACKET_TYPE_CODED_FRAMES
                frame = RtmpMuxer.FLV_FRAME_TYPE_KEY
                streamID = 1
                data = ByteBuffer.wrap(bytes)
            }.execute(stream.connection)
            executor.submit { stream.muxer.onInputBufferAvailable(MediaFormat.MIMETYPE_VIDEO_RAW, codec.codec!!, 0) }.get(2, TimeUnit.SECONDS)
            assertArrayEquals(bytes, consumed.get())

            assertTrue(stream.configure(record())) // Identical headers are harmless.
            assertEquals(1, configurations)
            val changed = "81080c000a0b00000042abbfc3700be001".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertFalse(stream.configure(Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(changed))))
            assertFalse(stream.canReceiveAv1)
            assertEquals(1, configurations)
            assertTrue(stream.isVideoCodecSwitch(MediaFormat.MIMETYPE_VIDEO_AVC))
            assertTrue(stream.isVideoCodecSwitch(MediaFormat.MIMETYPE_VIDEO_HEVC))
            assertTrue(stream.configure(record()))
            RtmpVideoMessage().apply {
                isExHeader = true
                fourCC = RtmpMuxer.FLV_VIDEO_FOUR_CC_AV01
                packetType = RtmpMuxer.FLV_VIDEO_PACKET_TYPE_SEQUENCE_START
                streamID = 1
                data = ByteBuffer.wrap(byteArrayOf(1))
            }.execute(stream.connection)
            assertFalse(stream.canReceiveAv1)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            stream.dispose()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun closingWhileWaitingForSurfaceDoesNotStartDecoder() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        val executor = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            val configuring = executor.submit<Boolean> { stream.configure(record()) }
            val source = screen.sources.poll(2, TimeUnit.SECONDS)!!
            stream.close()
            source.surface = surface
            assertFalse(configuring.get(2, TimeUnit.SECONDS))
            assertFalse(codec.isRunning.get())
            assertFalse(stream.canReceiveAv1)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            surface.release()
            texture.release()
        }
    }

    @Test
    fun closeAfterSurfaceDeliveryCannotBeOvertakenByDecoderStartup() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        val executor = Executors.newSingleThreadExecutor()
        val worker = AtomicReference<Thread>()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            val configuring =
                executor.submit<Boolean> {
                    worker.set(Thread.currentThread())
                    stream.configure(record())
                }
            val source = screen.sources.poll(2, TimeUnit.SECONDS)!!
            synchronized(stream) {
                source.surface = surface
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!configuring.isDone &&
                    (worker.get().state != Thread.State.BLOCKED || worker.get().stackTrace.firstOrNull()?.className != RtmpStream::class.java.name) &&
                    System.nanoTime() < deadline
                ) {
                    Thread.sleep(1)
                }
                assertFalse("Startup must wait while close owns the stream monitor", configuring.isDone)
                stream.close()
            }
            assertFalse(configuring.get(2, TimeUnit.SECONDS))
            assertFalse(codec.isRunning.get())
            assertFalse(stream.hasVideo)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            stream.dispose()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun av1HeaderCannotRelabelAnActiveAvcDecoder() {
        codec.inputMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        codec.isRunning.set(true)
        try {
            assertFalse(stream.configure(record()))
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, codec.inputMimeType)
            assertTrue(screen.sources.isEmpty())
            assertFalse(stream.canReceiveAv1)
        } finally {
            codec.isRunning.set(false)
        }
    }

    @Test fun hlgStartupUsesTheFirstSyncSequenceInsteadOfTheServerPlaceholder() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.dynamicRangeMode = com.haishinkit.graphics.DynamicRangeMode.HLG
        codec.listener = null
        val configured = AtomicReference<MediaFormat>()
        val configuredCsd = AtomicReference<ByteBuffer>()
        ShadowMediaCodec.addDecoder(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            ShadowMediaCodec.CodecConfig(
                4096,
                4096,
                object : ShadowMediaCodec.CodecConfig.Codec {
                    override fun onConfigured(
                        format: MediaFormat,
                        surface: Surface?,
                        crypto: MediaCrypto?,
                        flags: Int,
                    ) {
                        configured.set(format)
                        configuredCsd.set(format.getByteBuffer("csd-0")!!.duplicate())
                        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_RAW)
                    }

                    override fun process(
                        input: ByteBuffer,
                        output: ByteBuffer,
                    ) {}
                },
            ),
        )
        codec.codec = android.media.MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AV1)
        assertTrue(stream.configure(record())) // A valid but stale SDR placeholder must not start HLG decoding.
        assertNull(configured.get())
        assertFalse(stream.canReceiveAv1)
        val header = javaClass.getResourceAsStream("/hdr/pixel8-hlg-sequence.obu")!!.readBytes()
        assertFalse(stream.prepareAv1Frame(ByteBuffer.wrap(header), false))
        val executor = Executors.newSingleThreadExecutor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            val configuring = executor.submit<Boolean> { stream.prepareAv1Frame(ByteBuffer.wrap(byteArrayOf(0x12, 0) + header), true) }
            val source = screen.sources.poll(2, TimeUnit.SECONDS)!!
            source.surface = surface
            assertTrue(configuring.get(2, TimeUnit.SECONDS))
            val format = configured.get()
            assertEquals(320, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(180, format.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(2, format.getInteger(MediaFormat.KEY_PROFILE))
            val expected = byteArrayOf(0x81.toByte(), 0, 0x4c, 0) + header
            assertEquals(ByteBuffer.wrap(expected), configuredCsd.get())
            assertTrue(stream.canReceiveAv1)
        } finally {
            executor.shutdownNow()
            surface.release()
            texture.release()
        }
    }

    private fun record() = Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(fixture))

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
        override var videoEffect: VideoEffect = com.haishinkit.graphics.effect.DefaultVideoEffect.shared
        override var videoGravity = VideoGravity.RESIZE_ASPECT
        override var frameRate = 30
        override var backgroundColor = 0
    }
}
