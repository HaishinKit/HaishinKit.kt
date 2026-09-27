package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.lang.Running
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

/**
 * Base class for asynchronous Android media encoding and decoding.
 */
@Suppress("UNUSED")
abstract class Codec :
    MediaCodec.Callback(),
    Running {
    /**
     * Common codec configuration forwarded to the associated codec.
     */
    @Suppress("UNUSED")
    open class Setting(
        private var codec: Codec?,
    ) {
        /**
         * Additional [MediaFormat] options applied when the codec is configured.
         *
         * ```kotlin
         * stream.videoSetting.options = listOf(
         *     CodecOption(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
         * )
         * ```
         *
         * Option support depends on the selected Android codec. Set options before starting the stream.
         */
        var options: List<CodecOption> by Delegates.observable(listOf()) { _, _, newValue ->
            codec?.options = newValue
        }
    }

    /**
     * Receives input, format, and output callbacks from the codec.
     */
    interface Listener {
        /**
         * Notifies the listener that the codec input buffer at [index] is available.
         */
        fun onInputBufferAvailable(
            mime: String,
            codec: MediaCodec,
            index: Int,
        )

        /**
         * Notifies the listener of the output format for [mime].
         */
        fun onFormatChanged(
            mime: String,
            mediaFormat: MediaFormat,
        )

        /**
         * Receives an output sample and its timing and flag information.
         *
         * @return `true` to release the codec buffer immediately after this callback, or `false` if
         * its release is managed by the listener.
         */
        fun onSampleOutput(
            mime: String,
            index: Int,
            info: MediaCodec.BufferInfo,
            buffer: ByteBuffer,
        ): Boolean
    }

    /**
     * The listener for codec buffer and format callbacks, or `null`.
     */
    var listener: Listener? = null

    /**
     * The android.media.MediaCodec instance.
     */
    open var codec: MediaCodec? = null
        get() {
            if (field == null) {
                field =
                    if (mode == MODE_ENCODE) {
                        MediaCodec.createEncoderByType(outputMimeType)
                    } else {
                        MediaCodec.createDecoderByType(inputMimeType)
                    }
            }
            return field
        }
        set(value) {
            if (value == field) return
            field?.stop()
            field?.release()
            field = value
        }

    /**
     * The processing mode: [MODE_ENCODE] or [MODE_DECODE]. Set before starting the codec.
     */
    var mode = MODE_ENCODE

    /**
     * Additional media format options applied during [configure].
     */
    var options = listOf<CodecOption>()

    /**
     * The surface for a video media codec.
     */
    var surface: Surface? = null
        set(value) {
            field = value
            if (isRunning.get()) {
                when (mode) {
                    MODE_ENCODE -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            value?.let { codec?.setInputSurface(it) }
                        }
                    }

                    MODE_DECODE -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            value?.let { codec?.setOutputSurface(it) }
                        }
                    }

                    else -> {
                    }
                }
            }
        }

    /**
     * Specifies the input mime type.
     */
    abstract var inputMimeType: String

    /**
     * Specifies the output mime type.
     */
    abstract var outputMimeType: String

    override val isRunning = AtomicBoolean(false)
    private var outputFormat: MediaFormat? = null
        set(value) {
            if (field != value && value != null) {
                Log.i(TAG, value.toString())
                field = value
                value.getString("mime")?.let { mime ->
                    listener?.onFormatChanged(mime, value)
                }
            }
            field = value
        }
    private var backgroundHandler: Handler? = null
        get() {
            if (field == null) {
                val thread = HandlerThread(javaClass.name)
                thread.start()
                field = Handler(thread.looper)
            }
            return field
        }
        set(value) {
            field?.looper?.quitSafely()
            field = value
        }

    @Synchronized
    final override fun startRunning() {
        if (isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning($inputMimeType)")
        }
        try {
            val codec = codec ?: return
            configure(codec)
            outputFormat?.let { format ->
                format.getString("mime")?.let { mime ->
                    listener?.onFormatChanged(mime, format)
                }
            }
            codec.start()
            isRunning.set(true)
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "", e)
        }
    }

    @Synchronized
    final override fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning($inputMimeType)")
        }
        try {
            isRunning.set(false)
            dispose()
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "", e)
        }
    }

    /**
     * Releases the Android codec, callback thread, and cached output format.
     */
    open fun dispose() {
        codec = null
        backgroundHandler = null
        outputFormat = null
    }

    /**
     * Installs callbacks and configures the codec with its media format, options, surface, and processing mode.
     */
    open fun configure(codec: MediaCodec) {
        if (Build.VERSION_CODES.M <= Build.VERSION.SDK_INT) {
            codec.setCallback(this, backgroundHandler)
        } else {
            codec.setCallback(this)
        }
        val format =
            createMediaFormat(
                if (mode == MODE_ENCODE) {
                    outputMimeType
                } else {
                    inputMimeType
                },
            )
        for (option in options) {
            option.apply(format)
        }
        codec.configure(
            format,
            surface,
            null,
            if (mode == MODE_ENCODE) {
                MediaCodec.CONFIGURE_FLAG_ENCODE
            } else {
                0
            },
        )
        codec.outputFormat.getString("mime")?.let { mime ->
            outputMimeType = mime
        }
    }

    override fun onInputBufferAvailable(
        codec: MediaCodec,
        index: Int,
    ) {
        if (!isRunning.get()) return
        try {
            listener?.onInputBufferAvailable(outputMimeType, codec, index)
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, e)
            }
        }
    }

    override fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        if (!isRunning.get()) return
        try {
            val buffer = codec.getOutputBuffer(index) ?: return
            if (listener?.onSampleOutput(outputMimeType, index, info, buffer) == true) {
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "$index/${info.flags}", e)
            }
        }
    }

    override fun onError(
        codec: MediaCodec,
        e: MediaCodec.CodecException,
    ) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, e.toString())
        }
    }

    override fun onOutputFormatChanged(
        codec: MediaCodec,
        format: MediaFormat,
    ) {
        outputFormat = format
    }

    protected abstract fun createMediaFormat(mime: String): MediaFormat

    companion object {
        const val MODE_ENCODE = 0
        const val MODE_DECODE = 1
        private val TAG = Codec::class.java.simpleName
    }
}
