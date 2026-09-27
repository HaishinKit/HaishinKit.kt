package com.haishinkit.media

import android.content.Context
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import java.io.FileDescriptor
import java.lang.ref.WeakReference

/**
 * Encodes media from a registered source and writes it to a file.
 *
 * Register the recorder with a mixer before recording. Attach capture sources and start the
 * mixer before using this example. The output path must be writable by the application.
 *
 * ```kotlin
 * val recorder = MediaRecorder(context)
 * mixer.registerOutput(recorder)
 * val output = java.io.File(context.filesDir, "output.mp4")
 * recorder.startRecording(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
 * // After capturing the desired media:
 * recorder.stopRecording()
 * mixer.unregisterOutput(recorder)
 * ```
 */
@Suppress("UNUSED", "MemberVisibilityCanBePrivate")
class MediaRecorder(
    context: Context,
) : MediaOutput {
    /**
     * Whether this recorder is currently recording audio and video.
     */
    var isRecording = false
        private set

    /**
     * Specifies the video codec settings.
     */
    val videoSetting: VideoCodec.Setting by lazy {
        VideoCodec.Setting(videoCodec)
    }

    /**
     * Specifies the audio codec settings.
     */
    val audioSetting: AudioCodec.Setting by lazy {
        AudioCodec.Setting(audioCodec)
    }

    override var dataSource: WeakReference<MediaOutputDataSource>? = null
        set(value) {
            field = value
            videoCodec.pixelTransform.screen = dataSource?.get()?.screen
        }

    private var muxer: MediaRecorderMuxer? = null
    private val audioCodec by lazy { AudioCodec() }
    private val videoCodec by lazy { VideoCodec(context) }

    /**
     * Starts recording to a file path.
     *
     * @param path A writable destination file path.
     * @param format An Android [MediaMuxer.OutputFormat] value.
     * @throws IllegalStateException If recording has already started or no data source is registered.
     */
    fun startRecording(
        path: String,
        format: Int,
    ) {
        if (muxer != null || dataSource == null) {
            throw IllegalStateException()
        }
        Log.i(TAG, "Start recordings to $path.")
        muxer = MediaRecorderMuxer(dataSource, MediaMuxer(path, format))
        startRunning()
    }

    /**
     * Starts recording to a file descriptor. Requires Android 8.0 (API 26) or later.
     *
     * @param fd A writable file descriptor accepted by [MediaMuxer].
     * @param format An Android [MediaMuxer.OutputFormat] value.
     * @throws IllegalStateException If recording has already started or no data source is registered.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startRecording(
        fd: FileDescriptor,
        format: Int,
    ) {
        if (muxer != null || dataSource == null) {
            throw IllegalStateException()
        }
        muxer = MediaRecorderMuxer(dataSource, MediaMuxer(fd, format))
        startRunning()
    }

    /**
     * Stops recording and finalizes the output file.
     *
     * @throws IllegalStateException If recording has not started.
     */
    fun stopRecording() {
        if (muxer == null) {
            throw IllegalStateException()
        }
        muxer?.stopRunning()
        stopRunning()
        muxer = null
    }

    override fun append(buffer: MediaBuffer) {
        if (!isRecording) return
        buffer.payload?.let {
            audioCodec.append(it)
        }
    }

    private fun startRunning() {
        isRecording = true
        audioCodec.listener = muxer
        audioCodec.startRunning()
        videoCodec.listener = muxer
        videoCodec.startRunning()
    }

    private fun stopRunning() {
        audioCodec.stopRunning()
        audioCodec.listener = null
        videoCodec.stopRunning()
        videoCodec.listener = null
        isRecording = false
    }

    private companion object {
        private val TAG = MediaRecorder::class.java.simpleName
    }
}
