package com.haishinkit.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.haishinkit.codec.Codec
import com.haishinkit.media.BufferController
import com.haishinkit.media.MediaType
import com.haishinkit.metrics.FrameTracker
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.iso.Av1DecoderConfigurationRecord
import com.haishinkit.rtmp.iso.AvcDecoderConfigurationRecord
import com.haishinkit.rtmp.iso.DecoderConfigurationRecord
import com.haishinkit.rtmp.iso.OpusConfiguration
import com.haishinkit.rtmp.iso.VpDecoderConfigurationRecord
import com.haishinkit.rtmp.message.RtmpAudioMessage
import com.haishinkit.rtmp.message.RtmpVideoMessage
import com.haishinkit.util.MediaFormatUtil
import java.nio.ByteBuffer

internal class RtmpMuxer(
    private val stream: RtmpStream,
) : BufferController.Listener,
    Codec.Listener {
    @Volatile
    private var vpxConfigurationSent = false
    private var vpxLevel = 0
    private var pendingAv1Configuration: Av1DecoderConfigurationRecord? = null
    private var av1ConfigurationSent = false
    private var bufferTime = BufferController.DEFAULT_BUFFER_TIME
        set(value) {
            if (value == field) return
            audioBufferController.bufferTime = bufferTime
            videoBufferController.bufferTime = bufferTime
            field = value
        }
    private var audioTimestamp = 0L
    private var audioTimestampInitialized = false

    @Volatile
    private var opusConfigurationSent = false
    private var videoTimestamp = 0L
    private var frameTracker: FrameTracker? = null
        get() {
            if (field == null && BuildConfig.DEBUG) {
                field = FrameTracker()
            }
            return field
        }
    private val keyframes = mutableMapOf<Long, Boolean>()
    private val audioBufferController: BufferController<RtmpAudioMessage> by lazy {
        val controller = BufferController<RtmpAudioMessage>("audio")
        controller.bufferTime = bufferTime
        controller.listener = this
        controller
    }
    private val videoBufferController: BufferController<RtmpVideoMessage> by lazy {
        val controller = BufferController<RtmpVideoMessage>("video")
        controller.bufferTime = bufferTime
        controller.listener = this
        controller
    }

    fun enqueueAudio(message: RtmpAudioMessage) {
        audioBufferController.enqueue(message, message.timestamp)
    }

    fun enqueueVideo(message: RtmpVideoMessage) {
        videoBufferController.enqueue(message, message.timestamp)
    }

    fun clear() {
        vpxConfigurationSent = false
        pendingAv1Configuration = null
        av1ConfigurationSent = false
        audioTimestamp = 0L
        audioTimestampInitialized = false
        opusConfigurationSent = false
        videoTimestamp = 0L
        frameTracker?.clear()
        audioBufferController.clear()
        videoBufferController.clear()
    }

    override fun onInputBufferAvailable(
        mime: String,
        codec: MediaCodec,
        index: Int,
    ) {
        try {
            when (mime) {
                MediaFormat.MIMETYPE_VIDEO_RAW -> {
                    val message = videoBufferController.take()
                    videoBufferController.consume(message.timestamp)
                    stream.withInputBuffer(MediaType.VIDEO, codec, index) { inputBuffer ->
                        val success =
                            message.data?.let {
                                if (it.remaining() <= inputBuffer.remaining()) {
                                    inputBuffer.put(it)
                                    true
                                } else {
                                    Log.w(TAG, "BufferOverrun will drop a RTMPVideoMessage")
                                    false
                                }
                            } == true
                        videoTimestamp += message.timestamp * 1000
                        if (success) {
                            if (message.frame == FLV_FRAME_TYPE_KEY) keyframes[videoTimestamp] = true
                            // Only the codec payload was copied; exclude the RTMP video header.
                            codec.queueInputBuffer(index, 0, inputBuffer.position(), videoTimestamp, message.toFlags())
                        }
                    }
                }
                MediaFormat.MIMETYPE_AUDIO_RAW -> {
                    audioBufferController.stop()
                    val message = audioBufferController.take()
                    audioBufferController.consume(message.timestamp)
                    stream.withInputBuffer(MediaType.AUDIO, codec, index) { inputBuffer ->
                        val data = message.data?.duplicate()
                        if (data != null && data.remaining() <= inputBuffer.remaining()) {
                            inputBuffer.put(data)
                            codec.queueInputBuffer(index, 0, inputBuffer.position(), message.presentationTimeUs, message.toFlags())
                        } else {
                            Log.w(TAG, "Audio input buffer too small; dropping packet")
                            codec.queueInputBuffer(index, 0, 0, message.presentationTimeUs, 0)
                        }
                    }
                }
            }
        } catch (cause: InterruptedException) {
            // Codec teardown interrupts the retired callback thread's input waits.
            if (BuildConfig.DEBUG) Log.d(TAG, "Input callback stopped")
        }
    }

    override fun onFormatChanged(
        mime: String,
        mediaFormat: MediaFormat,
    ) {
        when (mime) {
            MediaFormat.MIMETYPE_VIDEO_RAW -> {
                if (!stream.acceptHlgOutputFormat(mediaFormat)) return
                if (stream.requiresSurfaceDecoder(stream.inputVideoMimeType) &&
                    mediaFormat.containsKey(MediaFormat.KEY_WIDTH) && mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)
                ) {
                    stream.updateVideoSize(
                        Size(MediaFormatUtil.getWidth(mediaFormat), MediaFormatUtil.getHeight(mediaFormat)),
                    )
                }
                stream.dispatchEventWith(
                    Event.RTMP_STATUS,
                    false,
                    RtmpStream.Code.VIDEO_DIMENSION_CHANGE.data(""),
                )
            }

            MediaFormat.MIMETYPE_VIDEO_AVC -> {
                val config = AvcDecoderConfigurationRecord.create(mediaFormat)
                stream.doOutput(
                    RtmpChunk.ZERO,
                    stream.messageFactory.createRtmpVideoMessage().apply {
                        isExHeader = false
                        packetType = FLV_AVC_PACKET_TYPE_SEQ
                        frame = FLV_FRAME_TYPE_KEY
                        codec = FLV_VIDEO_CODEC_AVC
                        data = config.toByteBuffer()
                        chunkStreamID = RtmpChunk.VIDEO
                        streamID = stream.id
                        timestamp = 0
                        compositeTime = 0
                    },
                )
            }

            MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9 -> {
                // One sequence header per publication; repeated format callbacks must not reset its clock.
                val level = if (mediaFormat.containsKey("level")) mediaFormat.getInteger("level") else 0
                val levels = intArrayOf(10, 11, 20, 21, 30, 31, 40, 41, 50, 51, 52, 60, 61, 62)
                vpxLevel =
                    if (mime == MediaFormat.MIMETYPE_VIDEO_VP9 && level > 0 && level and (level - 1) == 0) {
                        levels.getOrElse(Integer.numberOfTrailingZeros(level)) { 0 }
                    } else {
                        0
                    }
            }

            // Enhanced RTMP
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            -> {
                val config = DecoderConfigurationRecord.create(mime, mediaFormat) ?: return
                if (config is Av1DecoderConfigurationRecord) {
                    if (!config.hasSequenceHeader) {
                        pendingAv1Configuration = config
                        return
                    }
                    av1ConfigurationSent = true
                }
                stream.doOutput(
                    RtmpChunk.ZERO,
                    stream.messageFactory.createRtmpVideoMessage().apply {
                        isExHeader = true
                        frame = FLV_FRAME_TYPE_KEY
                        packetType = FLV_VIDEO_PACKET_TYPE_SEQUENCE_START
                        fourCC = getVideoFourCCByType(mime)
                        data = config.toByteBuffer()
                        chunkStreamID = RtmpChunk.VIDEO
                        streamID = stream.id
                        timestamp = 0
                        compositeTime = 0
                    },
                )
            }

            MediaFormat.MIMETYPE_AUDIO_RAW -> {
                stream.mediaLink.audioTrack = MediaFormatUtil.createAudioTrack(mediaFormat)
            }

            MediaFormat.MIMETYPE_AUDIO_AAC -> {
                val config = mediaFormat.getByteBuffer(CSD0) ?: return
                stream.doOutput(
                    RtmpChunk.ZERO,
                    stream.messageFactory.createRtmpAudioMessage().apply {
                        codec = FLV_AUDIO_CODEC_AAC
                        aacPacketType = FLV_AAC_PACKET_TYPE_SEQ
                        data = config
                        chunkStreamID = RtmpChunk.AUDIO
                        streamID = stream.id
                        timestamp = 0
                    },
                )
            }
            MediaFormat.MIMETYPE_AUDIO_OPUS -> {
                if (opusConfigurationSent) return
                val config =
                    mediaFormat.getByteBuffer(CSD0)?.let {
                        OpusConfiguration.fromEncoderCsd(it, mediaFormat.getByteBuffer("csd-1"))
                    } ?: return
                stream.doOutput(
                    RtmpChunk.ZERO,
                    stream.messageFactory.createRtmpAudioMessage().apply {
                        codec = FLV_AUDIO_CODEC_EX_HEADER
                        fourCC = FLV_AUDIO_FOUR_CC_OPUS
                        aacPacketType = FLV_AAC_PACKET_TYPE_SEQ
                        data = config.toByteBuffer()
                        chunkStreamID = RtmpChunk.AUDIO
                        streamID = stream.id
                        timestamp = 0
                    },
                )
                opusConfigurationSent = true
            }
        }
    }

    override fun onSampleOutput(
        mime: String,
        index: Int,
        info: MediaCodec.BufferInfo,
        buffer: ByteBuffer,
    ): Boolean {
        when (mime) {
            MediaFormat.MIMETYPE_VIDEO_RAW -> {
                if (!stream.canRenderVideo) return true
                val isKeyframe = info.flags == 1 || keyframes[info.presentationTimeUs] == true
                if (isKeyframe) {
                    keyframes.remove(info.presentationTimeUs)
                }
                stream.queueOutputBuffer(
                    MediaType.VIDEO,
                    index,
                    buffer,
                    info.presentationTimeUs,
                    isKeyframe,
                )
                return false
            }

            MediaFormat.MIMETYPE_VIDEO_AVC -> {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    return true
                }
                frameTracker?.track(FrameTracker.TYPE_VIDEO, SystemClock.uptimeMillis())
                if (videoTimestamp == 0L) {
                    videoTimestamp = info.presentationTimeUs
                }
                val timestamp = (info.presentationTimeUs - videoTimestamp).toInt()
                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val video = stream.messageFactory.createRtmpVideoMessage()
                stream.doOutput(
                    RtmpChunk.ONE,
                    video.apply {
                        isExHeader = false
                        packetType = FLV_AVC_PACKET_TYPE_NAL
                        frame = if (keyframe) FLV_FRAME_TYPE_KEY else FLV_FRAME_TYPE_INTER
                        codec = FLV_VIDEO_CODEC_AVC
                        data = buffer
                        chunkStreamID = RtmpChunk.VIDEO
                        this.timestamp = timestamp / 1000
                        streamID = stream.id
                        compositeTime = 0
                    },
                )
                stream.frameCount.incrementAndGet()
                videoTimestamp += video.timestamp * 1000
                return true
            }

            // Enhanced RTMP
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            -> {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    return true
                }
                val vpx = mime == MediaFormat.MIMETYPE_VIDEO_VP8 || mime == MediaFormat.MIMETYPE_VIDEO_VP9
                val codedBuffer =
                    if (vpx || mime == MediaFormat.MIMETYPE_VIDEO_AV1) {
                        if (info.offset < 0 || info.size <= 0 || info.size > buffer.capacity() - info.offset) return true
                        buffer.duplicate().apply {
                            limit(info.offset + info.size)
                            position(info.offset)
                        }.slice()
                    } else {
                        buffer
                    }
                frameTracker?.track(FrameTracker.TYPE_VIDEO, SystemClock.uptimeMillis())
                if (videoTimestamp == 0L) {
                    videoTimestamp = info.presentationTimeUs
                }
                val timestamp = (info.presentationTimeUs - videoTimestamp).toInt()
                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (mime == MediaFormat.MIMETYPE_VIDEO_AV1 && !av1ConfigurationSent) {
                    if (!keyframe) return true
                    val config =
                        try {
                            pendingAv1Configuration?.withSequenceHeader(codedBuffer) ?: Av1DecoderConfigurationRecord.fromKeyFrame(codedBuffer)
                        } catch (cause: IllegalArgumentException) {
                            null
                        } ?: return true
                    stream.doOutput(
                        RtmpChunk.ZERO,
                        stream.messageFactory.createRtmpVideoMessage().apply {
                            isExHeader = true
                            frame = FLV_FRAME_TYPE_KEY
                            packetType = FLV_VIDEO_PACKET_TYPE_SEQUENCE_START
                            fourCC = FLV_VIDEO_FOUR_CC_AV01
                            data = config.toByteBuffer()
                            chunkStreamID = RtmpChunk.VIDEO
                            streamID = stream.id
                            this.timestamp = 0
                        },
                    )
                    pendingAv1Configuration = null
                    av1ConfigurationSent = true
                }
                if ((mime == MediaFormat.MIMETYPE_VIDEO_VP8 || mime == MediaFormat.MIMETYPE_VIDEO_VP9) && !vpxConfigurationSent) {
                    if (!keyframe) return true
                    val record =
                        try {
                            VpDecoderConfigurationRecord.fromKeyFrame(mime, codedBuffer, vpxLevel)
                        } catch (cause: IllegalArgumentException) {
                            Log.w(TAG, "Invalid VP keyframe header", cause)
                            return true
                        }
                    stream.doOutput(
                        RtmpChunk.ZERO,
                        stream.messageFactory.createRtmpVideoMessage().apply {
                            isExHeader = true
                            frame = FLV_FRAME_TYPE_KEY
                            packetType = FLV_VIDEO_PACKET_TYPE_SEQUENCE_START
                            fourCC = getVideoFourCCByType(mime)
                            data = record.toByteBuffer()
                            chunkStreamID = RtmpChunk.VIDEO
                            streamID = stream.id
                            this.timestamp = 0
                            compositeTime = 0
                        },
                    )
                    vpxConfigurationSent = true
                }
                val video = stream.messageFactory.createRtmpVideoMessage()
                stream.doOutput(
                    RtmpChunk.ONE,
                    video.apply {
                        isExHeader = true
                        frame = if (keyframe) FLV_FRAME_TYPE_KEY else FLV_FRAME_TYPE_INTER
                        packetType =
                            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                                FLV_VIDEO_PACKET_TYPE_CODED_FRAMES_X
                            } else {
                                FLV_VIDEO_PACKET_TYPE_CODED_FRAMES
                            }
                        fourCC = getVideoFourCCByType(mime)
                        data = codedBuffer
                        chunkStreamID = RtmpChunk.VIDEO
                        streamID = stream.id
                        this.timestamp = timestamp / 1000
                        compositeTime = 0
                    },
                )
                stream.frameCount.incrementAndGet()
                videoTimestamp += video.timestamp * 1000
                return true
            }

            MediaFormat.MIMETYPE_AUDIO_RAW -> {
                stream.queueOutputBuffer(
                    MediaType.AUDIO,
                    index,
                    buffer,
                    info.presentationTimeUs,
                    true,
                )
                return false
            }

            MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_OPUS -> {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    return true
                }
                if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS && !opusConfigurationSent) return true
                if (info.offset < 0 || info.size <= 0 || info.size > buffer.capacity() - info.offset) return true
                val codedBuffer =
                    buffer.duplicate().apply {
                        limit(info.offset + info.size)
                        position(info.offset)
                    }.slice()
                frameTracker?.track(FrameTracker.TYPE_AUDIO, SystemClock.uptimeMillis())
                if (!audioTimestampInitialized) {
                    audioTimestamp = info.presentationTimeUs
                    audioTimestampInitialized = true
                }
                val timestamp = (info.presentationTimeUs - audioTimestamp) / 1000
                if (timestamp < 0 || timestamp > Int.MAX_VALUE) return true
                val audio = stream.messageFactory.createRtmpAudioMessage()
                stream.doOutput(
                    RtmpChunk.ONE,
                    audio.apply {
                        codec = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) FLV_AUDIO_CODEC_EX_HEADER else FLV_AUDIO_CODEC_AAC
                        fourCC = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) FLV_AUDIO_FOUR_CC_OPUS else 0
                        aacPacketType = FLV_AAC_PACKET_TYPE_RAW
                        data = codedBuffer
                        chunkStreamID = RtmpChunk.AUDIO
                        this.timestamp = timestamp.toInt()
                        streamID = stream.id
                    },
                )
                audioTimestamp += audio.timestamp.toLong() * 1000
                return true
            }
        }
        return true
    }

    override fun <T> onBufferFull(controller: BufferController<T>) {
        if (controller == videoBufferController) {
            if (stream.receiveVideo) {
                stream.mediaLink.paused = false
            } else {
                return
            }
        }
        stream.dispatchEventWith(Event.RTMP_STATUS, false, RtmpStream.Code.BUFFER_FULL.data(""))
    }

    override fun <T> onBufferEmpty(controller: BufferController<T>) {
        if (controller == videoBufferController) {
            if (stream.receiveVideo) {
                stream.mediaLink.paused = true
            } else {
                return
            }
        }
        stream.dispatchEventWith(Event.RTMP_STATUS, false, RtmpStream.Code.BUFFER_EMPTY.data(""))
    }

    @Suppress("UNUSED")
    companion object {
        const val CSD0 = "csd-0"
        const val FLV_AAC_PACKET_TYPE_SEQ: Byte = 0x00
        const val FLV_AAC_PACKET_TYPE_RAW: Byte = 0x01
        const val FLV_AUDIO_PACKET_TYPE_SEQUENCE_END: Byte = 0x02
        const val FLV_AUDIO_CODEC_EX_HEADER: Byte = 0x09
        const val FLV_AUDIO_FOUR_CC_OPUS = 0x4F707573

        const val FLV_FRAME_TYPE_KEY: Byte = 0x01
        const val FLV_FRAME_TYPE_INTER: Byte = 0x02
        const val FLV_FRAME_TYPE_DISPOSABLE: Byte = 0x03
        const val FLV_FRAME_TYPE_GENERATED: Byte = 0x04
        const val FLV_FRAME_TYPE_COMMAND: Byte = 0x05

        const val FLV_AVC_PACKET_TYPE_SEQ: Byte = 0x00
        const val FLV_AVC_PACKET_TYPE_NAL: Byte = 0x01
        const val FLV_AVC_PACKET_TYPE_EOS: Byte = 0x02

        const val FLV_VIDEO_CODEC_SORENSON_H263: Byte = 0x02
        const val FLV_VIDEO_CODEC_SCREEN1: Byte = 0x03
        const val FLV_VIDEO_CODEC_ON2_VP6: Byte = 0x04
        const val FLV_VIDEO_CODEC_ON2_VP6_ALPHA: Byte = 0x05
        const val FLV_VIDEO_CODEC_SCREEN_2: Byte = 0x06
        const val FLV_VIDEO_CODEC_AVC: Byte = 0x07

        const val FLV_AUDIO_CODEC_ADPCM: Byte = 0x01
        const val FLV_AUDIO_CODEC_MP3: Byte = 0x02
        const val FLV_AUDIO_CODEC_PCMLE: Byte = 0x03
        const val FLV_AUDIO_CODEC_NELLYMOSER_16K: Byte = 0x04
        const val FLV_AUDIO_CODEC_NELLYMOSER_8K: Byte = 0x05
        const val FLV_AUDIO_CODEC_NELLYMOSER: Byte = 0x06
        const val FLV_AUDIO_CODEC_G711A: Byte = 0x07
        const val FLV_AUDIO_CODEC_G711MU: Byte = 0x08
        const val FLV_AUDIO_CODEC_AAC: Byte = 0x0A
        const val FLV_AUDIO_CODEC_SPEEX: Byte = 0x0B
        const val FLV_AUDIO_CODEC_MP3_8K: Byte = 0x0E

        const val FLV_VIDEO_FOUR_CC_AV01: Int = 0x61763031 // { 'a', 'v', '0', '1' }
        const val FLV_VIDEO_FOUR_CC_VP08: Int = 0x76703038 // { 'v', 'p', '0', '8' }
        const val FLV_VIDEO_FOUR_CC_VP09: Int = 0x76703039 // { 'v', 'p', '0', '9' }
        const val FLV_VIDEO_FOUR_CC_HVC1: Int = 0x68766331 // { 'h', 'v', 'c', '1' }

        const val FLV_VIDEO_PACKET_TYPE_SEQUENCE_START: Byte = 0
        const val FLV_VIDEO_PACKET_TYPE_CODED_FRAMES: Byte = 1
        const val FLV_VIDEO_PACKET_TYPE_SEQUENCE_END: Byte = 2
        const val FLV_VIDEO_PACKET_TYPE_CODED_FRAMES_X: Byte = 3
        const val FLV_VIDEO_PACKET_TYPE_METADATA: Byte = 4
        const val FLV_VIDEO_PACKET_TYPE_MPEG2TS_SEQUENCE_START: Byte = 5

        private const val VERBOSE = false
        private val TAG = RtmpMuxer::class.java.simpleName

        fun isNonNalVideo(type: String): Boolean =
            type == MediaFormat.MIMETYPE_VIDEO_AV1 || type == MediaFormat.MIMETYPE_VIDEO_VP8 || type == MediaFormat.MIMETYPE_VIDEO_VP9

        fun getVideoFourCCByType(type: String): Int =
            when (type) {
                MediaFormat.MIMETYPE_VIDEO_AV1 -> FLV_VIDEO_FOUR_CC_AV01
                MediaFormat.MIMETYPE_VIDEO_HEVC -> FLV_VIDEO_FOUR_CC_HVC1
                MediaFormat.MIMETYPE_VIDEO_VP8 -> FLV_VIDEO_FOUR_CC_VP08
                MediaFormat.MIMETYPE_VIDEO_VP9 -> FLV_VIDEO_FOUR_CC_VP09
                else -> 0
            }

        fun getTypeByVideoFourCC(fourCC: Int): String? =
            when (fourCC) {
                FLV_VIDEO_FOUR_CC_HVC1 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                FLV_VIDEO_FOUR_CC_VP08 -> MediaFormat.MIMETYPE_VIDEO_VP8
                FLV_VIDEO_FOUR_CC_VP09 -> MediaFormat.MIMETYPE_VIDEO_VP9
                FLV_VIDEO_FOUR_CC_AV01 ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaFormat.MIMETYPE_VIDEO_AV1
                    } else {
                        null
                    }

                else -> null
            }

        fun isSupportedVideoFourCC(fourCC: Int): Boolean =
            when (fourCC) {
                FLV_VIDEO_FOUR_CC_HVC1 -> true
                FLV_VIDEO_FOUR_CC_VP08, FLV_VIDEO_FOUR_CC_VP09 -> true
                FLV_VIDEO_FOUR_CC_AV01 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                else -> false
            }
    }
}
