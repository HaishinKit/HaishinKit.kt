package com.haishinkit.media

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
 * ### Code
 * ```kotlin
 * var recorder = MediaMixerRecorder(context)
 * if (recorder.isRecording) {
 *   recorder.stopRecording()
 * } else {
 *   recorder.attachMediaMixer(mixer)
 *   recorder.startRecording(
 *     File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "output.mp4").toString(),
 *     MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
 *   )
 * }
 * ```
 */
@Suppress("UNUSED", "MemberVisibilityCanBePrivate")
class MediaRecorder(
    applicationContext: Context,
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

    private var muxer: MediaRecorderMuxer? = null
    private var mixer: MediaMixer? = null
    private val audioCodec by lazy { AudioCodec() }
    private val videoCodec by lazy { VideoCodec(applicationContext) }

    /**
     * Attaches the media mixer.
     */
    fun attachMediaMixer(mixer: MediaMixer?) {
        this.mixer = mixer
        videoCodec.pixelTransform.screen = mixer?.screen
    }

    /**
     * Starts recording.
     */
    fun startRecording(
        path: String,
        format: Int,
    ) {
        if (muxer != null || mixer == null) {
            throw IllegalStateException()
        }
        Log.i(TAG, "Start recordings to $path.")
        muxer = MediaRecorderMuxer(mixer, MediaMuxer(path, format))
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
        if (muxer != null || mixer == null) {
            throw IllegalStateException()
        }
        muxer = MediaRecorderMuxer(mixer, MediaMuxer(fd, format))
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
        mixer?.audioSource?.registerAudioCodec(audioCodec)
    }

    private fun stopRunning() {
        mixer?.audioSource?.unregisterAudioCodec(audioCodec)
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
