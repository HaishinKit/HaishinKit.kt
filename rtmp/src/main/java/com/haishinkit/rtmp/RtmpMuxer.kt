package com.haishinkit.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.haishinkit.codec.Codec
import com.haishinkit.media.BufferController
import com.haishinkit.media.MediaType
import com.haishinkit.metrics.FrameTracker
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.iso.AvcDecoderConfigurationRecord
import com.haishinkit.rtmp.iso.DecoderConfigurationRecord
import com.haishinkit.rtmp.message.RtmpAudioMessage
import com.haishinkit.rtmp.message.RtmpVideoMessage
import com.haishinkit.util.MediaFormatUtil
import java.nio.ByteBuffer

internal class RtmpMuxer(
    private val stream: RtmpStream,
) : BufferController.Listener,
    Codec.Listener {
    private var bufferTime = BufferController.DEFAULT_BUFFER_TIME
        set(value) {
            if (value == field) return
            audioBufferController.bufferTime = bufferTime
            videoBufferController.bufferTime = bufferTime
            field = value
        }
    private var audioTimestamp = 0L
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
        audioTimestamp = 0L
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
        when (mime) {
            MediaFormat.MIMETYPE_VIDEO_RAW -> {
                try {
                    val inputBuffer = codec.getInputBuffer(index) ?: return
                    val message = videoBufferController.take()
                    videoBufferController.consume(message.timestamp)
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
                        // There are some devices where info.flags always become 0, so this is a workaround.
                        if (message.frame == FLV_FRAME_TYPE_KEY) {
                            keyframes[videoTimestamp] = true
                        }
                        codec.queueInputBuffer(
                            index,
                            0,
                            message.length,
                            videoTimestamp,
                            message.toFlags(),
                        )
                    }
                } catch (e: InterruptedException) {
                    Log.w(TAG, "", e)
                }
            }

            MediaFormat.MIMETYPE_AUDIO_RAW -> {
                try {
                    val inputBuffer = codec.getInputBuffer(index) ?: return
                    audioBufferController.stop()
                    val message = audioBufferController.take()
                    audioBufferController.consume(message.timestamp)
                    message.data?.let {
                        it.position(1)
                        inputBuffer.put(it)
                    }
                    audioTimestamp += message.timestamp * 1000
                    codec.queueInputBuffer(
                        index,
                        0,
                        message.length - 2,
                        audioTimestamp,
                        message.toFlags(),
                    )
                } catch (e: InterruptedException) {
                    Log.w(TAG, "", e)
                }
            }

            else -> {
            }
        }
    }

    override fun onFormatChanged(
        mime: String,
        mediaFormat: MediaFormat,
    ) {
        when (mime) {
            MediaFormat.MIMETYPE_VIDEO_RAW -> {
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

            // Enhanced RTMP
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            -> {
                val config = DecoderConfigurationRecord.create(mime, mediaFormat) ?: return
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
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            -> {
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
                        isExHeader = true
                        frame = if (keyframe) FLV_FRAME_TYPE_KEY else FLV_FRAME_TYPE_INTER
                        packetType =
                            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                                FLV_VIDEO_PACKET_TYPE_CODED_FRAMES_X
                            } else {
                                FLV_VIDEO_PACKET_TYPE_CODED_FRAMES
                            }
                        fourCC = getVideoFourCCByType(mime)
                        data = buffer
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

            MediaFormat.MIMETYPE_AUDIO_AAC -> {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    return true
                }
                frameTracker?.track(FrameTracker.TYPE_AUDIO, SystemClock.uptimeMillis())
                if (audioTimestamp == 0L) {
                    audioTimestamp = info.presentationTimeUs
                }
                val timestamp = (info.presentationTimeUs - audioTimestamp).toInt()
                val audio = stream.messageFactory.createRtmpAudioMessage()
                stream.doOutput(
                    RtmpChunk.ONE,
                    audio.apply {
                        codec = FLV_AUDIO_CODEC_AAC
                        aacPacketType = FLV_AAC_PACKET_TYPE_RAW
                        data = buffer
                        chunkStreamID = RtmpChunk.AUDIO
                        this.timestamp = timestamp / 1000
                        streamID = stream.id
                    },
                )
                audioTimestamp += audio.timestamp * 1000
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
        const val FLV_VIDEO_FOUR_CC_VP09: Int = 0x76703039 // { 'v', 'p', '0', '9' }
        const val FLV_VIDEO_FOUR_CC_HVC1: Int = 0x68766331 // { 'h', 'v', 'c', '1' }

        const val FLV_VIDEO_PACKET_TYPE_SEQUENCE_START: Byte = 0
        const val FLV_VIDEO_PACKET_TYPE_CODED_FRAMES: Byte = 1
        const val FLV_VIDEO_PACKET_TYPE_SEQUENCE_END: Byte = 2
        const val FLV_VIDEO_PACKET_TYPE_CODED_FRAMES_X: Byte = 3
        const val FLV_VIDEO_PACKET_TYPE_METADATA: Byte = 4
        const val FLV_VIDEO_PACKET_TYPE_MPEG2TS_SEQUENCE_START: Byte = 5

        fun getVideoFourCCByType(type: String): Int =
            when (type) {
                MediaFormat.MIMETYPE_VIDEO_AV1 -> FLV_VIDEO_FOUR_CC_AV01
                MediaFormat.MIMETYPE_VIDEO_HEVC -> FLV_VIDEO_FOUR_CC_HVC1
                MediaFormat.MIMETYPE_VIDEO_VP9 -> FLV_VIDEO_FOUR_CC_VP09
                else -> 0
            }

        fun getTypeByVideoFourCC(fourCC: Int): String? =
            when (fourCC) {
                FLV_VIDEO_FOUR_CC_HVC1 -> MediaFormat.MIMETYPE_VIDEO_HEVC
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
                FLV_VIDEO_FOUR_CC_VP09 -> false
                FLV_VIDEO_FOUR_CC_AV01 -> false
                else -> false
            }

        private const val VERBOSE = false
        private val TAG = RtmpMuxer::class.java.simpleName
    }
}
