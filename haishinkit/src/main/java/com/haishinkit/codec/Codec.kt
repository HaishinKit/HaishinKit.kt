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
import com.haishinkit.media.MediaLink
import java.nio.ByteBuffer
import java.util.Deque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

@Suppress("UNUSED")
abstract class Codec :
    MediaCodec.Callback(),
    Running {
    @Suppress("UNUSED")
    open class Setting(
        private var codec: Codec?,
    ) {
        /**
         * Specifies the [MediaCodec]'s [MediaFormat] options if necessary.
         *
         * ```kotlin
         * var options = mutableListOf<CodecOption>()
         * options.add(CodecOption(KEY_LOW_LATENCY, 0))
         * options.add(CodecOption(KEY_TEMPORAL_LAYERING, "android.generic.N, android.generic.N+M"))
         * netStream.videoSettings.options = options
         * ```
         */
        var options: List<CodecOption> by Delegates.observable(listOf()) { _, _, newValue ->
            codec?.options = newValue
        }
    }

    interface Listener {
        fun onInputBufferAvailable(
            mime: String,
            codec: MediaCodec,
            index: Int,
        )

        fun onFormatChanged(
            mime: String,
            mediaFormat: MediaFormat,
        )

        fun onSampleOutput(
            mime: String,
            index: Int,
            info: MediaCodec.BufferInfo,
            buffer: ByteBuffer,
        ): Boolean
    }

    /**
     * The listener of which callback method.
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
            field?.stop()
            field?.setCallback(null)
            field?.release()
            field = value
        }

    /**
     * Specifies the mode of encoding or decoding.
     */
    var mode = MODE_ENCODE

    /**
     * Specifies the external android.media.MediaCodec options.
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
        private set(value) {
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
            dispose()
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "", e)
        }
        isRunning.set(false)
    }

    open fun dispose() {
        codec = null
        backgroundHandler = null
        outputFormat = null
    }

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
        try {
            val buffer = codec.getOutputBuffer(index) ?: return
            if (listener?.onSampleOutput(outputMimeType, index, info, buffer) == true) {
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, e)
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

    internal fun release(buffers: Deque<MediaLink.Buffer>) {
        val it = buffers.iterator()
        while (it.hasNext()) {
            val buffer = it.next()
            codec?.releaseOutputBuffer(buffer.index, false)
            it.remove()
        }
    }

    protected abstract fun createMediaFormat(mime: String): MediaFormat

    companion object {
        const val MODE_ENCODE = 0
        const val MODE_DECODE = 1
        private val TAG = Codec::class.java.simpleName
    }
}
