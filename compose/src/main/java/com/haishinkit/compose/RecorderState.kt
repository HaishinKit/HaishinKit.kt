@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaRecorder

@Composable
fun rememberRecorderState(
    context: Context,
    mixer: MediaMixer,
): RecorderState =
    remember(context) {
        RecorderState(mixer, MediaRecorder(context))
    }

@Stable
class RecorderState(
    mixer: MediaMixer,
    private val recorder: MediaRecorder,
) {
    var isRecording by mutableStateOf(false)
        private set

    init {
        recorder.attachMediaMixer(mixer)
    }

    fun startRecording(
        path: String,
        format: Int,
    ) {
        isRecording = true
        recorder.startRecording(path, format)
    }

    fun stopRecording() {
        recorder.stopRecording()
        isRecording = false
    }
}
