package com.haishinkit.rtmp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.source.VideoSource
import com.haishinkit.net.NetSocket
import com.haishinkit.net.NetSocketImpl
import com.haishinkit.rtmp.iso.VpDecoderConfigurationRecord
import com.haishinkit.rtmp.message.RtmpVideoMessage
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class VpPublicationTest {
    @Test
    fun vp8PublicationSendsConfigurationThenUnmodifiedFrame() = checkPublication("vp8")

    @Test
    fun vp9PublicationSendsConfigurationThenUnmodifiedFrame() = checkPublication("vp9")

    private fun checkPublication(name: String) {
        val mime = "video/x-vnd.on2.$name"
        val original = javaClass.getResourceAsStream("/vpx/$name-keyframe.bin")!!.use { it.readBytes() }
        val packets = mutableListOf<ByteArray>()
        val transport =
            object : NetSocket by NetSocketImpl() {
                override fun doOutput(buffer: ByteBuffer) {
                    val copy = buffer.duplicate().apply { flip() }
                    packets.add(ByteArray(copy.remaining()).also { copy.get(it) })
                }
            }
        val oldScreen = Screen.factory
        val oldTransform = PixelTransform.factory
        Screen.factory = { TestScreen(it) }
        PixelTransform.factory = { TestTransform(it) }
        val connection = RtmpConnection()
        RtmpSocket::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(connection.socket, transport)
        connection.socket.chunkSizeS = 65536
        val stream = RtmpStream(RuntimeEnvironment.getApplication(), connection)
        try {
            stream.id = 1
            val format = MediaFormat.createVideoFormat(mime, 1280, 720)
            if (name == "vp9") format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.VP9Level31)
            stream.muxer.onFormatChanged(mime, format)
            assertEquals(0, packets.size) // No encoder CSD is needed to produce a header.
            val info = MediaCodec.BufferInfo().apply { set(0, 1, 1_000_000, 0) }
            stream.muxer.onSampleOutput(mime, 0, info, ByteBuffer.wrap(byteArrayOf(1)))
            assertEquals(0, packets.size) // Wait for the first real keyframe.
            val padded = ByteBuffer.wrap(byteArrayOf(7, 8, 9) + original + byteArrayOf(10, 11))
            info.set(3, original.size, 1_033_333, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            stream.muxer.onSampleOutput(mime, 0, info, padded)
            assertEquals(0, padded.position())
            assertEquals(2, packets.size)
            val header = decode(packets[0])
            assertEquals(RtmpMuxer.FLV_VIDEO_PACKET_TYPE_SEQUENCE_START, header.packetType)
            assertEquals(RtmpMuxer.getVideoFourCCByType(mime), header.fourCC)
            val record = VpDecoderConfigurationRecord.decode(mime, header.data!!)
            assertEquals(0, record.profile)
            val coded = decode(packets[1])
            assertEquals(RtmpMuxer.FLV_VIDEO_PACKET_TYPE_CODED_FRAMES, coded.packetType)
            assertEquals(33, coded.timestamp)
            assertArrayEquals(original, coded.data!!.array())
            writeFlv(name, listOf(packets[0], packets[1]))
            val interframe = byteArrayOf(0x86.toByte(), 1, 2, 3)
            info.set(0, interframe.size, 1_066_666, 0)
            stream.muxer.onSampleOutput(mime, 0, info, ByteBuffer.wrap(interframe))
            assertEquals(3, packets.size)
            assertArrayEquals(interframe, decode(packets[2]).data!!.array())
            assertEquals(33, decode(packets[2]).timestamp)
            stream.muxer.onFormatChanged(mime, format)
            info.set(0, interframe.size, 1_099_999, 0)
            stream.muxer.onSampleOutput(mime, 0, info, ByteBuffer.wrap(interframe))
            assertEquals(4, packets.size)
            assertEquals(RtmpMuxer.FLV_VIDEO_PACKET_TYPE_CODED_FRAMES, decode(packets[3]).packetType)
            assertEquals(33, decode(packets[3]).timestamp)
        } finally {
            stream.dispose()
            Screen.factory = oldScreen
            PixelTransform.factory = oldTransform
        }
    }

    private fun decode(packet: ByteArray): RtmpVideoMessage {
        val type = (packet[0].toInt() ushr 6) and 3
        val offset = if (type == 0) 12 else 8
        val body = ByteBuffer.wrap(packet, offset, packet.size - offset).slice()
        val time = ((packet[1].toInt() and 255) shl 16) or ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
        return RtmpVideoMessage().apply {
            length = body.remaining()
            timestamp = time
            decode(body)
        }
    }

    private fun writeFlv(
        name: String,
        packets: List<ByteArray>,
    ) {
        val file = File("build/vpx-smoke-$name.flv").apply { parentFile?.mkdirs() }
        DataOutputStream(file.outputStream()).use { out ->
            out.write(byteArrayOf(0x46, 0x4C, 0x56, 1, 1, 0, 0, 0, 9, 0, 0, 0, 0))
            for (packet in packets) {
                val type = (packet[0].toInt() ushr 6) and 3
                val offset = if (type == 0) 12 else 8
                val size = packet.size - offset
                out.writeByte(9)
                out.writeByte(size ushr 16)
                out.writeByte(size ushr 8)
                out.writeByte(size)
                out.write(packet, 1, 3)
                out.writeByte(0)
                out.write(byteArrayOf(0, 0, 0))
                out.write(packet, offset, size)
                out.writeInt(size + 11)
            }
        }
    }

    private class TestScreen(context: Context) : Screen(context) {
        override fun readPixels(lambda: (Bitmap?) -> Unit) {}

        override fun bind(screenObject: ScreenObject) {}

        override fun unbind(screenObject: ScreenObject) {}

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) {}
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
