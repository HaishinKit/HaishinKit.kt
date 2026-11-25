package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import com.haishinkit.codec.AudioCodec
import com.haishinkit.media.BufferController
import com.haishinkit.media.source.VideoSource
import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.message.RtmpAudioMessage
import com.haishinkit.rtmp.message.RtmpCommandMessage
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.stream.Stream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.BlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class OpusStreamTest {
    private val originalFactory = Screen.factory
    private lateinit var stream: RtmpStream
    private val codec: AudioCodec
        get() = Stream::class.java.getDeclaredMethod("getAudioCodec").apply { isAccessible = true }.invoke(stream) as AudioCodec

    @Before fun setUp() {
        Screen.factory = { TestScreen(it) }
        ShadowMediaCodecList.reset()
        for (encoding in listOf(false, true)) {
            val capabilities =
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2))
                    .setIsEncoder(encoding).build()
            ShadowMediaCodecList.addCodec(
                MediaCodecInfoBuilder.newBuilder().setName("test.opus.$encoding")
                    .setIsEncoder(encoding).setCapabilities(capabilities).build(),
            )
        }
        stream = RtmpStream(RuntimeEnvironment.getApplication(), RtmpConnection())
        stream.id = 1
        stream.connection.streams[1] = stream
    }

    @After fun tearDown() {
        stream.dispose()
        ShadowMediaCodec.clearCodecs()
        ShadowMediaCodecList.reset()
        Screen.factory = originalFactory
    }

    @Test fun publishingSendsHeaderSlicedPacketsAndZeroTimeline() {
        val packets = capture()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2).apply { setByteBuffer("csd-0", header()) }
        stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_AUDIO_OPUS, format)
        stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_AUDIO_OPUS, format)
        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x4f, 0x70, 0x75, 0x73) + header().array(), body(packets[0]))
        val encoded = ByteBuffer.wrap(byteArrayOf(99, 99, 0xf8.toByte(), 0xff.toByte(), 0xfe.toByte(), 99))
        for (time in listOf(0L, 20_000L)) {
            val info = MediaCodec.BufferInfo().apply { set(2, 3, time, 0) }
            assertTrue(stream.muxer.onSampleOutput(MediaFormat.MIMETYPE_AUDIO_OPUS, 0, info, encoded))
        }
        assertArrayEquals(byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()), body(packets[1]))
        assertEquals(20, timestamp(packets[2]))
        assertEquals(0, encoded.position())
        stream.muxer.clear()
        stream.muxer.onSampleOutput(MediaFormat.MIMETYPE_AUDIO_OPUS, 0, MediaCodec.BufferInfo().apply { set(0, 3, 0, 0) }, encoded)
        assertEquals(3, packets.size)
    }

    @Test fun capabilitiesAndMetadataAdvertiseOpus() {
        val command = stream.connection.createConnectionMessage(URI("rtmp://localhost/live")) as RtmpCommandMessage
        assertEquals(3, (command.commandObject!!["audioFourCcInfoMap"] as Map<*, *>)["Opus"])
        assertTrue((command.commandObject!!["fourCcList"] as List<*>).contains("Opus"))
        stream.hasAudio = true
        stream.audioSetting.mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
        val metadata = RtmpStream::class.java.getDeclaredMethod("toMetaData").apply { isAccessible = true }.invoke(stream) as Map<*, *>
        assertEquals(RtmpMuxer.FLV_AUDIO_FOUR_CC_OPUS, metadata["audiocodecid"])
    }

    @Test fun publishingUnwrapsAndroidCsdAndNormalizesItsDelay() {
        val packets = capture()
        val inner =
            header().apply {
                putShort(10, 104)
                putInt(12, 16000)
            }
        val csd =
            ByteBuffer.allocate(16 + inner.remaining() + 24).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("AOPUSHDR".toByteArray())
                putLong(inner.remaining().toLong())
                put(inner.duplicate())
                put("AOPUSDLY".toByteArray())
                putLong(8)
                putLong(6_500_000)
                flip()
            }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16000, 2).apply { setByteBuffer("csd-0", csd) }
        stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_AUDIO_OPUS, format)
        assertEquals(1, packets.size)
        val expected =
            inner.array().copyOf().apply {
                this[10] = 0x38
                this[11] = 1
            }
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x4f, 0x70, 0x75, 0x73) + expected, body(packets.single()))
        assertEquals(0, csd.position())
    }

    @Test fun unpublishingUsesTheNormalCloseCommandWithoutAnExtraAudioEndPacket() {
        val packets = capture()
        stream.readyState = RtmpStream.ReadyState.PUBLISHING
        stream.muxer.onFormatChanged(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2).apply { setByteBuffer("csd-0", header()) },
        )
        packets.clear()
        stream.publish(null)
        assertEquals(1, packets.size)
        assertEquals(20, packets.single()[7].toInt()) // RTMP command, not an audio SequenceEnd.
    }

    @Test fun invalidOpusPublishingFailsBeforeTouchingTheConnection() {
        val packets = capture()
        stream.hasAudio = true
        stream.audioSetting.mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
        assertThrows(IllegalArgumentException::class.java) { stream.publish("invalid-rate") }
        assertTrue(stream.connection.isConnected)
        assertTrue(packets.isEmpty())
        assertTrue(stream.messages.isEmpty())
        stream.audioSetting.sampleRate = 48000
        ShadowMediaCodecList.reset()
        assertThrows(IllegalArgumentException::class.java) { stream.publish("missing-encoder") }
        assertTrue(stream.connection.isConnected)
        assertTrue(packets.isEmpty())
    }

    @Test fun nativeAacGetsTheCompleteHeAacV2AndProgramConfigHeaders() {
        for (bytes in listOf(byteArrayOf(0xEB.toByte(), 0x8A.toByte(), 0x08, 0), byteArrayOf(0x12, 0, 0x05, 0x04, 0, 0, 0x20, 0))) {
            val caps =
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)).setIsEncoder(false).build()
            ShadowMediaCodecList.addCodec(
                MediaCodecInfoBuilder.newBuilder().setName("test.aac.decoder")
                    .setIsEncoder(false).setCapabilities(caps).build(),
            )
            stream.readyState = RtmpStream.ReadyState.PLAY
            codec.listener = null
            assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_AAC, ByteBuffer.wrap(bytes)))
            val csd = codec.options.first { it.key == "csd-0" }.value as ByteBuffer
            assertArrayEquals(bytes, csd.array())
            codec.stopRunning()
        }
    }

    @Test fun playbackConfiguresSharedAudioCodecAndRejectsChangingSequence() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header()))
        assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, codec.inputMimeType)
        assertTrue(codec.isRunning.get())
        assertTrue(stream.canReceiveAudio(MediaFormat.MIMETYPE_AUDIO_OPUS))
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header()))
        assertFalse(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header().apply { put(10, 1) }))
        assertFalse(stream.canReceiveAudio(MediaFormat.MIMETYPE_AUDIO_OPUS))
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header()))
        stream.endAudioSequence()
        assertFalse(stream.canReceiveAudio(MediaFormat.MIMETYPE_AUDIO_OPUS))
        assertFalse(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header()))
    }

    @Test fun payloadAndTimestampsAreOwnedAndMissingHeadersDoNotQueue() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        val message = RtmpAudioMessage()
        decode(message, byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 1), 20, RtmpChunk.ONE)
        assertEquals(0, queued().size)
        decode(message, byteArrayOf(0x90.toByte(), 0x4f, 0x70, 0x75, 0x73) + header().array(), 1_320_000, RtmpChunk.ZERO)
        decode(message, byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 1), 20, RtmpChunk.ONE)
        decode(message, byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 2), 40, RtmpChunk.TWO)
        val first = queued().poll()!!
        val second = queued().poll()!!
        assertArrayEquals(byteArrayOf(1), first.data!!.array())
        assertArrayEquals(byteArrayOf(2), second.data!!.array())
        assertEquals(1_320_020_000L, first.presentationTimeUs)
        assertEquals(1_320_060_000L, second.presentationTimeUs)
        decode(message, byteArrayOf(0x90.toByte(), 0x4f), 0, RtmpChunk.ONE)
        decode(message, byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 3), 20, RtmpChunk.ONE)
        assertEquals(0, queued().size)
    }

    private fun capture(): MutableList<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val transport =
            object : NetSocket by NetSocketImpl() {
                override fun doOutput(buffer: ByteBuffer) {
                    buffer.flip()
                    packets.add(ByteArray(buffer.remaining()).also { buffer.get(it) })
                }
            }
        RtmpSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(stream.connection.socket, transport)
        RtmpSocket::class.java.getDeclaredField("isConnected").apply { isAccessible = true }.setBoolean(stream.connection.socket, true)
        return packets
    }

    private fun decode(
        message: RtmpAudioMessage,
        data: ByteArray,
        time: Int,
        type: RtmpChunk,
    ) {
        message.length = data.size
        message.streamID = 1
        message.timestamp = time
        message.chunk = type
        message.decode(ByteBuffer.wrap(data)).execute(stream.connection)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queued(): BlockingQueue<RtmpAudioMessage> {
        val controller = RtmpMuxer::class.java.getDeclaredMethod("getAudioBufferController").apply { isAccessible = true }.invoke(stream.muxer)
        return BufferController::class.java.getDeclaredField("messages").apply { isAccessible = true }.get(controller) as BlockingQueue<RtmpAudioMessage>
    }

    private fun header() =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1)
            put(2)
            putShort(0)
            putInt(48000)
            putShort(0)
            put(0)
            flip()
        }

    private fun body(bytes: ByteArray) = bytes.copyOfRange(if ((bytes[0].toInt() and 0xc0) == 0) 12 else 8, bytes.size)

    private fun timestamp(bytes: ByteArray) = ((bytes[1].toInt() and 255) shl 16) or ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)

    private class TestScreen(context: Context) : Screen(context) {
        override fun readPixels(lambda: (Bitmap?) -> Unit) {}

        override fun bind(screenObject: ScreenObject) {}

        override fun unbind(screenObject: ScreenObject) {}

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) {}
    }
}
