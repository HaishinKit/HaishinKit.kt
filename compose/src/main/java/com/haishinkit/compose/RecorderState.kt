@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamRecorder

@Composable
fun rememberRecorderState(
    context: Context,
    stream: Stream,
): RecorderState =
    remember(context) {
        RecorderState(stream, StreamRecorder(context))
    }

@Stable
class RecorderState(
    stream: Stream,
    private val recorder: StreamRecorder,
) {
    var isRecording by mutableStateOf(false)
        private set

    init {
        recorder.attachStream(stream)
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
