package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import com.haishinkit.codec.VideoCodecProfileLevel
import com.haishinkit.media.BufferController
import com.haishinkit.media.source.VideoSource
import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.amf.AmfEcmaArray
import com.haishinkit.rtmp.amf.AmfTypeBuffer
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
        val metadata = RtmpStream::class.java.getDeclaredMethod("toMetaData").apply { isAccessible = true }.invoke(stream) as AmfEcmaArray
        assertEquals(RtmpMuxer.FLV_AUDIO_FOUR_CC_OPUS, metadata["audiocodecid"])
    }

    @Test fun metadataEncodesNumericCodecIdsInEcmaArray() {
        val videoCodec = Stream::class.java.getDeclaredMethod("getVideoCodec").apply { isAccessible = true }.invoke(stream) as VideoCodec
        stream.hasVideo = true
        stream.hasAudio = true
        val videoProfiles =
            listOf(
                VideoCodecProfileLevel.H264_BASELINE_3_1 to 7,
                VideoCodecProfileLevel.HEVC_MAIN_3_1 to 0x68766331,
                VideoCodecProfileLevel.AV1_MAIN8_3_1 to 0x61763031,
                VideoCodecProfileLevel.VP8_MAIN to 0x76703038,
                VideoCodecProfileLevel.VP9_PROFILE0_31 to 0x76703039,
            )
        for ((profile, videoId) in videoProfiles) {
            videoCodec.profileLevel = profile
            for ((mime, audioId) in listOf(MediaFormat.MIMETYPE_AUDIO_AAC to 10, MediaFormat.MIMETYPE_AUDIO_OPUS to 0x4f707573)) {
                stream.audioSetting.mimeType = mime
                val metadata = RtmpStream::class.java.getDeclaredMethod("toMetaData").apply { isAccessible = true }.invoke(stream)
                val buffer = ByteBuffer.allocate(4096)
                AmfTypeBuffer(buffer).putData(metadata)
                buffer.flip()
                assertEquals(8, buffer.get(0).toInt()) // AMF0 ECMA array, not an object or string.
                val decoded = AmfTypeBuffer(buffer).data as AmfEcmaArray
                assertEquals(videoId.toDouble(), decoded["videocodecid"])
                assertEquals(audioId.toDouble(), decoded["audiocodecid"])
                assertEquals(videoCodec.width.toDouble(), decoded["width"])
            }
        }
        stream.hasVideo = false
        stream.hasAudio = false
        val empty = RtmpStream::class.java.getDeclaredMethod("toMetaData").apply { isAccessible = true }.invoke(stream) as AmfEcmaArray
        assertFalse(empty.iterator().hasNext())
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

    @Test fun socketAcceptsRepeatedType3OpusMessagesAndType2AfterAbsoluteTime() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        val headerBytes = byteArrayOf(0x90.toByte(), 0x4f, 0x70, 0x75, 0x73) + header().array()
        val audio = byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        val wire =
            ByteBuffer.allocate(256).apply {
                putInt(4 shl 24)
                putInt((headerBytes.size shl 8) or 8)
                putInt(Integer.reverseBytes(1))
                put(headerBytes)
                put(0x44)
                put(byteArrayOf(0, 0, 20))
                put(byteArrayOf(0, 0, audio.size.toByte(), 8))
                put(audio)
                put(0x84.toByte())
                put(byteArrayOf(0, 0, 20))
                put(audio)
                repeat(2) {
                    put(0xC4.toByte())
                    put(audio)
                }
                flip()
            }
        stream.connection.listen(wire)
        assertFalse(wire.hasRemaining())
        assertEquals(4, queued().size)
        assertEquals(listOf(20_000L, 40_000L, 60_000L, 80_000L), queued().map { it.presentationTimeUs })
        queued().clear()
        val absolute =
            ByteBuffer.allocate(64).apply {
                putInt((4 shl 24) or 1000)
                putInt((audio.size shl 8) or 8)
                putInt(Integer.reverseBytes(1))
                put(audio)
                put(0x84.toByte())
                put(byteArrayOf(0, 0, 20))
                put(audio)
                flip()
            }
        stream.connection.listen(absolute)
        assertEquals(listOf(1_000_000L, 1_020_000L), queued().map { it.presentationTimeUs })
    }

    @Test fun aacConfigurationChangesAreQueuedBeforeTheFollowingFrames() {
        val caps =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)).setIsEncoder(false).build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("test.aac.decoder")
                .setIsEncoder(false).setCapabilities(caps).build(),
        )
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_AAC, ByteBuffer.wrap(byteArrayOf(0x12, 0x10))))
        val originalDecoder = codec.codec
        val packet = RtmpAudioMessage()
        decode(packet, byteArrayOf(0xAF.toByte(), 0, 0x11, 0x90.toByte()), 0, RtmpChunk.ZERO)
        decode(packet, byteArrayOf(0xAF.toByte(), 1, 1, 2), 20, RtmpChunk.ONE)
        assertTrue(stream.canReceiveAudio(MediaFormat.MIMETYPE_AUDIO_AAC))
        assertTrue(originalDecoder === codec.codec)
        val config = queued().poll()!!
        val frame = queued().poll()!!
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), config.data!!.array())
        assertEquals(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, config.toFlags())
        assertArrayEquals(byteArrayOf(1, 2), frame.data!!.array())
        assertEquals(0, frame.toFlags())
    }

    @Test fun audioReceiveClockSurvivesSignedAndUnsigned32BitBoundaries() {
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header()))
        val packet = RtmpAudioMessage()
        val raw = byteArrayOf(0x91.toByte(), 0x4f, 0x70, 0x75, 0x73, 0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        decode(packet, raw, 0x7FFF_FFF0, RtmpChunk.ZERO)
        decode(packet, raw, 20, RtmpChunk.ONE)
        assertEquals(listOf(2_147_483_632_000L, 2_147_483_652_000L), queued().map { it.presentationTimeUs })
        queued().clear()
        // A fresh receive clock must accept the full unsigned first absolute time.
        stream.close()
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_OPUS, header()))
        decode(packet, raw, 0xFFFF_FFF0.toInt(), RtmpChunk.ZERO)
        decode(packet, raw, 4, RtmpChunk.ZERO)
        decode(packet, raw, 20, RtmpChunk.TWO)
        assertEquals(listOf(4_294_967_280_000L, 4_294_967_300_000L, 4_294_967_320_000L), queued().map { it.presentationTimeUs })
    }

    @Test fun aacPlaybackDoesNotChangeTheNextEncoderProfile() {
        val caps =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)).setIsEncoder(false).build()
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder().setName("test.aac.decoder")
                .setIsEncoder(false).setCapabilities(caps).build(),
        )
        stream.readyState = RtmpStream.ReadyState.PLAY
        codec.listener = null
        assertTrue(stream.configureAudio(MediaFormat.MIMETYPE_AUDIO_AAC, ByteBuffer.wrap(byteArrayOf(0x0A, 0x10))))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectLC, codec.aacProfile)
        stream.close()
        codec.mode = com.haishinkit.codec.Codec.MODE_ENCODE
        val format =
            AudioCodec::class.java.getDeclaredMethod("createMediaFormat", String::class.java)
                .apply { isAccessible = true }.invoke(codec, MediaFormat.MIMETYPE_AUDIO_AAC) as MediaFormat
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectLC, format.getInteger(MediaFormat.KEY_AAC_PROFILE))
    }

    @Test fun av1PublicationWithoutCsdBuildsSequenceStartFromTheSyncFrame() {
        val packets = capture()
        val sequence = javaClass.getResourceAsStream("/hdr/pixel8-hlg-sequence.obu")!!.readBytes()
        val bytes = byteArrayOf(0x12, 0) + sequence + byteArrayOf(0x32, 1, 0)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AV1, 320, 180)
        stream.muxer.onFormatChanged(MediaFormat.MIMETYPE_VIDEO_AV1, format)
        assertTrue(packets.isEmpty())
        val buffer = ByteBuffer.wrap(bytes)
        val info = MediaCodec.BufferInfo().apply { set(0, bytes.size, 1_000_000, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
        assertTrue(stream.muxer.onSampleOutput(MediaFormat.MIMETYPE_VIDEO_AV1, 0, info, buffer))
        assertEquals(2, packets.size)
        assertEquals(0, buffer.position())
        val header = body(packets[0])
        assertEquals(0x90, header[0].toInt() and 255)
        val record = com.haishinkit.rtmp.iso.Av1DecoderConfigurationRecord.decode(ByteBuffer.wrap(header.copyOfRange(5, header.size)))
        assertTrue(record.isHlg)
        assertArrayEquals(bytes, body(packets[1]).copyOfRange(5, body(packets[1]).size))
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
