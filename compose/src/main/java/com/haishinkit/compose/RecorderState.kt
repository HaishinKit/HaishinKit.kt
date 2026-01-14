@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.media.MediaRecorder

@Composable
fun rememberRecorderState(
    context: Context,
    dataSource: MediaOutputDataSource,
): RecorderState =
    remember(context) {
        RecorderState(dataSource, MediaRecorder(context))
    }

@Stable
class RecorderState(
    dataSource: MediaOutputDataSource,
    private val recorder: MediaRecorder,
) {

    var isRecording by mutableStateOf(false)
        private set

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
