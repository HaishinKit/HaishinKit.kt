package com.haishinkit.rtmp.message

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.core.util.Pools
import com.haishinkit.media.BufferController
import com.haishinkit.rtmp.RtmpChunk
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpMuxer
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

internal class RtmpAudioMessage(
    pool: Pools.Pool<RtmpMessage>? = null,
) : RtmpMessage(TYPE_AUDIO, pool) {
    var codec: Byte = 0
    var soundRate: Byte = 0
    var soundSize: Byte = 0
    var soundType: Byte = 0
    var data: ByteBuffer? = null
    var aacPacketType: Byte = 0
    var fourCC: Int = 0
    var presentationTimeUs: Long = 0
    val headerSize: Int
        get() = if (codec == RtmpMuxer.FLV_AUDIO_CODEC_EX_HEADER) 5 else 2
    override var length: Int
        get() = if (super.length == -1) headerSize + (data?.remaining() ?: 0) else super.length
        set(value) {
            super.length = value
        }

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        if (codec == RtmpMuxer.FLV_AUDIO_CODEC_EX_HEADER) {
            buffer.put(((codec.toInt() shl 4) or aacPacketType.toInt()).toByte())
            buffer.putInt(fourCC)
        } else {
            buffer.put(AAC)
            buffer.put(aacPacketType)
        }
        data?.let { buffer.put(it.duplicate()) }
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        // The connection retries a TCP fragment from its RTMP chunk header on underflow.
        if (length > buffer.remaining()) throw BufferUnderflowException()
        data = null
        fourCC = 0
        aacPacketType = -1
        codec = -1
        if (length <= 0 || !buffer.hasRemaining()) return this
        val size = length
        val bytes = buffer.slice().apply { limit(size) }
        buffer.position(buffer.position() + size)
        val first = bytes.get().toInt() and 255
        codec = (first shr 4).toByte()
        soundRate = ((first and 12) shr 2).toByte()
        soundSize = ((first and 2) shr 1).toByte()
        soundType = (first and 1).toByte()
        if (codec == RtmpMuxer.FLV_AUDIO_CODEC_EX_HEADER) {
            aacPacketType = (first and 15).toByte()
            if (length < 5 || bytes.remaining() < 4) return this
            fourCC = bytes.int
        } else {
            if (codec != RtmpMuxer.FLV_AUDIO_CODEC_AAC || length < 2 || !bytes.hasRemaining()) return this
            aacPacketType = bytes.get()
        }
        if (length - headerSize > bytes.remaining()) return this
        data = ByteBuffer.wrap(ByteArray(length - headerSize).also { bytes.get(it) })
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage {
        val stream = connection.streams[streamID] ?: return this
        val previous = stream.audioTimestamp
        val raw = timestamp.toLong() and 0xFFFF_FFFFL
        val absolute =
            if (chunk == RtmpChunk.ZERO && previous != null) {
                var difference = raw - (previous and 0xFFFF_FFFFL)
                if (difference < -0x8000_0000L) difference += 0x1_0000_0000L
                if (difference > 0x8000_0000L) difference -= 0x1_0000_0000L
                previous + difference
            } else if (chunk == RtmpChunk.ZERO) {
                raw
            } else {
                (previous ?: 0L) + raw
            }
        val delta = absolute - (previous ?: 0L)
        stream.audioTimestamp = absolute
        val mime =
            when {
                codec == RtmpMuxer.FLV_AUDIO_CODEC_AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
                codec == RtmpMuxer.FLV_AUDIO_CODEC_EX_HEADER && fourCC == RtmpMuxer.FLV_AUDIO_FOUR_CC_OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
                else -> null
            }
        when (aacPacketType) {
            RtmpMuxer.FLV_AAC_PACKET_TYPE_SEQ -> {
                val updateAac = mime == MediaFormat.MIMETYPE_AUDIO_AAC && stream.isAacDecoderRunning
                stream.invalidateAudioConfiguration()
                val payload = data ?: return this
                if (mime != null && stream.configureAudio(mime, payload) && updateAac) {
                    stream.muxer.enqueueAudio(
                        RtmpAudioMessage().also {
                            it.codec = codec
                            it.aacPacketType = RtmpMuxer.FLV_AAC_PACKET_TYPE_SEQ
                            it.data = payload.duplicate()
                            it.presentationTimeUs = absolute * 1000
                        },
                    )
                }
            }
            RtmpMuxer.FLV_AAC_PACKET_TYPE_RAW -> {
                val payload = data ?: return this
                if (mime == null || delta < 0 || !stream.canReceiveAudio(mime) || !payload.hasRemaining()) return this
                // Chunk type 2/3 can reuse the message object. Own both the payload and timing.
                val snapshot =
                    RtmpAudioMessage().also {
                        it.codec = codec
                        it.aacPacketType = aacPacketType
                        it.fourCC = fourCC
                        // Buffer accounting only needs to reach the startup threshold;
                        // large timestamp gaps belong to the independent Long decoder PTS.
                        it.timestamp = delta.coerceAtMost(BufferController.DEFAULT_BUFFER_TIME.toLong()).toInt()
                        it.presentationTimeUs = absolute * 1000
                        it.data = payload.duplicate()
                    }
                stream.muxer.enqueueAudio(snapshot)
            }
            RtmpMuxer.FLV_AUDIO_PACKET_TYPE_SEQUENCE_END -> if (codec == RtmpMuxer.FLV_AUDIO_CODEC_EX_HEADER && mime != null) stream.endAudioSequence()
        }
        return this
    }

    override fun release(): Boolean {
        data = null
        codec = 0
        fourCC = 0
        aacPacketType = 0
        length = -1
        payload.clear()
        return super.release()
    }

    fun toFlags(): Int =
        if (codec == RtmpMuxer.FLV_AUDIO_CODEC_AAC && aacPacketType == RtmpMuxer.FLV_AAC_PACKET_TYPE_SEQ) {
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        } else {
            0
        }

    companion object {
        private const val AAC = (0x0A shl 4 or (0x03 shl 2) or (0x01 shl 1) or 0x01).toByte()
    }
}
