package com.haishinkit.stream

import android.content.Context
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.haishinkit.codec.AudioCodec
import com.haishinkit.codec.VideoCodec
import java.io.FileDescriptor

/**
 * An object that writes media data to a file.
 *
 * ## Usages.
 * ### AndroidManifest.xml
 * ```xml
 * <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
 * ```
 *
 * ### Code
 * ```kotlin
 * var recorder = [StreamRecorder](context)
 * if (recorder.isRecording) {
 *   recorder.stopRecording()
 * } else {
 *   recorder.attachStream(stream)
 *   recorder.startRecording(
 *     File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "output.mp4").toString(),
 *     MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
 *   )
 * }
 * ```
 */
@Suppress("UNUSED", "MemberVisibilityCanBePrivate")
class StreamRecorder(
    context: Context,
) {
    /**
     * The isRecording value indicates whether the audio recorder is recording.
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

    private var muxer: StreamRecorderMuxer? = null
    private var stream: Stream? = null
    private val audioCodec by lazy { AudioCodec() }
    private val videoCodec by lazy { VideoCodec(context) }

    /**
     * Attaches the stream.
     */
    fun attachStream(stream: Stream?) {
        this.stream = stream
        videoCodec.pixelTransform.screen = stream?.screen
    }

    /**
     * Starts recording.
     */
    fun startRecording(
        path: String,
        format: Int,
    ) {
        if (muxer != null || stream == null) {
            throw IllegalStateException()
        }
        Log.i(TAG, "Start recordings to $path.")
        muxer = StreamRecorderMuxer(stream, MediaMuxer(path, format))
        startRunning()
    }

    /**
     * Starts recording.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startRecording(
        fd: FileDescriptor,
        format: Int,
    ) {
        if (muxer != null || stream == null) {
            throw IllegalStateException()
        }
        muxer = StreamRecorderMuxer(stream, MediaMuxer(fd, format))
        startRunning()
    }

    /**
     * Stops recording.
     */
    fun stopRecording() {
        if (muxer == null) {
            throw IllegalStateException()
        }
        muxer?.stopRunning()
        stopRunning()
        muxer = null
    }

    private fun startRunning() {
        isRecording = true
        audioCodec.listener = muxer
        audioCodec.startRunning()
        videoCodec.listener = muxer
        videoCodec.startRunning()
        stream?.registerAudioCodec(audioCodec)
    }

    private fun stopRunning() {
        stream?.unregisterAudioCodec(audioCodec)
        audioCodec.stopRunning()
        audioCodec.listener = null
        videoCodec.stopRunning()
        videoCodec.listener = null
        isRecording = false
    }

    private companion object {
        private val TAG = StreamRecorder::class.java.simpleName
    }
}
