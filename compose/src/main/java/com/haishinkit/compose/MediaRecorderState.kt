@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.media.MediaRecorder

/**
 * Creates and remembers a [MediaRecorderState] for [recorder]. A different recorder creates new state.
 */
@Composable
fun rememberMediaRecorderState(recorder: MediaRecorder): MediaRecorderState =
    remember(recorder) {
        MediaRecorderState(recorder)
    }

/**
 * Exposes recording controls and Compose-observable state for a [MediaRecorder].
 *
 * Use this wrapper to start and stop recording. [isRecording] starts as `false` and is updated
 * only by calls through this wrapper; direct recorder operations are not observed.
 */
@Stable
class MediaRecorderState(
    private val recorder: MediaRecorder,
) {
    /**
     * The recording flag maintained by this wrapper. It is set before the start request is forwarded.
     */
    var isRecording by mutableStateOf(false)
        private set

    /**
     * Forwards a start request to the recorder after setting [isRecording] to `true`.
     *
     * @param path A writable destination file path.
     * @param format An Android `MediaMuxer.OutputFormat` value.
     * @throws IllegalStateException If the recorder is already recording or has no registered source.
     * The flag remains `true` if the start request throws.
     */
    fun startRecording(
        path: String,
        format: Int,
    ) {
        isRecording = true
        recorder.startRecording(path, format)
    }

    /**
     * Stops the recorder and sets [isRecording] to `false` after the call succeeds.
     *
     * @throws IllegalStateException If the recorder is not recording.
     */
    fun stopRecording() {
        recorder.stopRecording()
        isRecording = false
    }
}
