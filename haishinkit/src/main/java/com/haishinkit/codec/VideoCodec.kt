package com.haishinkit.codec

import android.content.Context
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Size
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.util.FeatureUtil
import com.haishinkit.util.MediaFormatUtil
import kotlin.properties.Delegates

/**
 * Encodes and decodes video using Android media codecs.
 */
class VideoCodec(
    context: Context,
) : Codec() {
    /**
     * Video encoder settings. Configure dimensions and profile before starting encoding.
     */
    @Suppress("UNUSED")
    data class Setting(
        private val codec: VideoCodec? = null,
    ) : Codec.Setting(codec) {
        /**
         * Specifies the video codec profile level.
         *
         * @throws IllegalArgumentException If no encoder supports the selected MIME type.
         */
        var profileLevel: VideoCodecProfileLevel by Delegates.observable(DEFAULT_PROFILE_LEVEL) { _, oldValue, newValue ->
            if (oldValue == newValue) return@observable
            if (!CodecCapabilities.isCodecSupportedByType(MODE_ENCODE, newValue.mime)) {
                throw IllegalArgumentException("Unsupported mime type for ${newValue.mime}.")
            }
            codec?.outputMimeType = newValue.mime
            codec?.profileLevel = newValue
        }

        /**
         * The requested output width, in pixels. Odd values are rounded down for the associated codec.
         */
        var width: Int by Delegates.observable(DEFAULT_WIDTH) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.width = if (newValue % 2 == 0) newValue else newValue - 1
            }
        }

        /**
         * The requested output height, in pixels. Odd values are rounded down for the associated codec.
         */
        var height: Int by Delegates.observable(DEFAULT_HEIGHT) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.height = if (newValue % 2 == 0) newValue else newValue - 1
            }
        }

        /**
         * The target video bit rate, in bits per second.
         */
        var bitRate: Int by Delegates.observable(DEFAULT_BIT_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.bitRate = newValue
            }
        }

        /**
         * The interval between keyframes, in seconds.
         */
        var IFrameInterval: Int by Delegates.observable(DEFAULT_I_FRAME_INTERVAL) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.IFrameInterval = newValue
            }
        }

        /**
         * The target video frame rate, in frames per second.
         */
        var frameRate: Int by Delegates.observable(DEFAULT_FRAME_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.frameRate = newValue
            }
        }

        /**
         * Specifies the scaling mode for a video output.
         */
        var videoGravity: VideoGravity by Delegates.observable(DEFAULT_VIDEO_GRAVITY) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.pixelTransform?.videoGravity = newValue
            }
        }
    }

    /**
     * The target video bit rate, in bits per second.
     */
    var bitRate = DEFAULT_BIT_RATE
        set(value) {
            field = value
            if (FeatureUtil.isEnabled(FeatureUtil.FEATURE_BITRATE_CHANGE) && isRunning.get()) {
                val bundle =
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value)
                    }
                codec?.setParameters(bundle)
            }
        }

    /**
     * The target video frame rate, in frames per second.
     */
    var frameRate = DEFAULT_FRAME_RATE
        set(value) {
            field = value
            pixelTransform.frameRate = value
        }

    /**
     * The interval between keyframes, in seconds.
     */
    var IFrameInterval = DEFAULT_I_FRAME_INTERVAL

    /**
     * The output video width, in pixels.
     */
    var width = DEFAULT_WIDTH

    /**
     * The output video height, in pixels.
     */
    var height = DEFAULT_HEIGHT

    /**
     * Specifies the profile for a video output.
     */
    var profileLevel: VideoCodecProfileLevel = DEFAULT_PROFILE_LEVEL

    /** Explicit HLG playback selection; decoding color metadata remains authoritative. */
    var dynamicRangeMode = DynamicRangeMode.SDR

    /**
     * The pixel transform instance.
     */
    val pixelTransform: PixelTransform by lazy {
        PixelTransform.create(context).apply {
            videoGravity = DEFAULT_VIDEO_GRAVITY
            frameRate = DEFAULT_FRAME_RATE
        }
    }

    /** Crop in the decoder's underlying buffer; right/bottom are exclusive. */
    val videoCropRect: Rect?
        get() = MediaFormatUtil.getCropRect(currentOutputFormat)

    override var inputMimeType = MediaFormat.MIMETYPE_VIDEO_RAW
    override var outputMimeType = DEFAULT_PROFILE_LEVEL.mime

    override var codec: MediaCodec?
        get() = super.codec
        set(value) {
            if (value == null) {
                pixelTransform.surface = null
            }
            super.codec = value
        }

    /**
     * Specifies the color format for a surface.
     */
    private var colorFormat = DEFAULT_COLOR_FORMAT

    private val isHlgEncoding: Boolean
        get() = pixelTransform.screen?.dynamicRangeMode == DynamicRangeMode.HLG

    override fun createMediaCodec(): MediaCodec {
        if (mode == MODE_DECODE && dynamicRangeMode == DynamicRangeMode.HLG) {
            val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(createMediaFormat(inputMimeType))
            requireNotNull(name) { "No decoder supports the requested 10-bit HLG format" }
            return MediaCodec.createByCodecName(name)
        }
        if (!isHlgEncoding) return super.createMediaCodec()
        require(Build.VERSION.SDK_INT >= 33) { "HLG encoding requires Android 13 or later" }
        require(pixelTransform is com.haishinkit.gles.ThreadPixelTransform) { "HLG encoding requires the OpenGL pixel transform" }
        return MediaCodec.createByCodecName(requireNotNull(findHlgEncoder()) { "No encoder supports the requested 10-bit HLG format" })
    }

    /** Rejects unsupported HLG settings before a publication request is sent. */
    fun validateEncodingSettings() {
        if (pixelTransform.screen?.dynamicRangeMode == DynamicRangeMode.HLG) {
            require(Build.VERSION.SDK_INT >= 33) { "HLG encoding requires Android 13 or later" }
            require(pixelTransform is com.haishinkit.gles.ThreadPixelTransform) { "HLG encoding requires the OpenGL pixel transform" }
            requireNotNull(findHlgEncoder()) { "No encoder supports the requested 10-bit HLG format" }
        }
    }

    override fun createMediaFormat(mime: String): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (mode == MODE_ENCODE) {
                if (isHlgEncoding) {
                    require(Build.VERSION.SDK_INT >= 33) { "HLG encoding requires Android 13 or later" }
                    val main10 =
                        when (mime) {
                            MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            MediaFormat.MIMETYPE_VIDEO_AV1 -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                            else -> throw IllegalArgumentException("HLG encoding supports HEVC and AV1 only")
                        }
                    require(profileLevel.profile == main10) { "Select a Main10 profile for HLG encoding" }
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate)
                setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFrameInterval)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_PROFILE, profileLevel.profile)
                if (Build.VERSION_CODES.M <= Build.VERSION.SDK_INT) {
                    setInteger(MediaFormat.KEY_LEVEL, profileLevel.level)
                } else {
                    setInteger("level", profileLevel.level)
                }
            } else {
                if (dynamicRangeMode == DynamicRangeMode.HLG) {
                    val profile =
                        when (mime) {
                            MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            MediaFormat.MIMETYPE_VIDEO_AV1 -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                            else -> throw IllegalArgumentException("HLG decoding supports HEVC and AV1 only")
                        }
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
                if (Build.VERSION_CODES.R <= Build.VERSION.SDK_INT && dynamicRangeMode == DynamicRangeMode.SDR) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
        }

    override fun configure(codec: MediaCodec) {
        super.configure(codec)
        if (mode == MODE_ENCODE) {
            if (isHlgEncoding) pixelTransform.isEncoderOutput = true
            pixelTransform.imageExtent = Size(width, height)
            pixelTransform.surface = codec.createInputSurface()
        }
    }

    private fun findHlgEncoder(): String? {
        val format = createMediaFormat(outputMimeType)
        val name =
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.contains(outputMimeType) &&
                    info.getCapabilitiesForType(outputMimeType).let { caps ->
                        (caps.isFeatureSupported("hdr-editing") || caps.isFeatureSupported("hlg-editing")) && caps.isFormatSupported(format)
                    }
            }?.name
        return name
    }

    companion object {
        val DEFAULT_PROFILE_LEVEL = VideoCodecProfileLevel.H264_BASELINE_3_2
        const val DEFAULT_BIT_RATE = 640 * 1000
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_I_FRAME_INTERVAL = 2
        const val DEFAULT_WIDTH = 854
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        val DEFAULT_VIDEO_GRAVITY = VideoGravity.RESIZE_ASPECT

        @Suppress("unused")
        private val TAG = VideoCodec::class.java.simpleName
    }
}
