package com.haishinkit.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

private const val TAG = "CameraControllerView"

@Suppress("ktlint:standard:function-naming")
@Composable
fun CameraControllerView(
    isRecording: Boolean,
    isConnected: Boolean,
    onClickScreenShot: () -> Unit,
    onClickConnect: () -> Unit,
    onClickRecording: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(onClick = {
            onClickScreenShot()
        }) {
            Text("Screenshot")
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = {
            onClickConnect()
        }) {
            if (isConnected) {
                Text("STOP")
            } else {
                Text("GO LIVE")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = {
            onClickRecording()
        }) {
            if (isRecording) {
                Text("Stop")
            } else {
                Text("Recording")
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Preview
@Composable
private fun PreviewCameraControllerView() {
    CameraControllerView(
        isRecording = false,
        isConnected = false,
        onClickConnect = {},
        onClickRecording = {},
        onClickScreenShot = {},
    )
}
