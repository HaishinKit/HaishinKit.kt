package com.haishinkit.app

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraDeviceControllerView(
    onAudioPermissionStatus: (state: PermissionState) -> Unit,
    onVideoPermissionStatus: (state: PermissionState) -> Unit,
) {
    // Audio permission settings.
    val audioPermissionState =
        rememberPermissionState(
            Manifest.permission.RECORD_AUDIO,
        )
    LaunchedEffect(audioPermissionState) {
        snapshotFlow { audioPermissionState.status }.collect {
            onAudioPermissionStatus.invoke(audioPermissionState)
        }
    }

    // Camera permission settings.
    val cameraPermissionState =
        rememberPermissionState(
            Manifest.permission.CAMERA,
        )
    LaunchedEffect(cameraPermissionState) {
        snapshotFlow { cameraPermissionState.status }.collect {
            onVideoPermissionStatus.invoke(cameraPermissionState)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(onClick = {
            when (cameraPermissionState.status) {
                PermissionStatus.Granted -> {
                }

                is PermissionStatus.Denied -> {
                    cameraPermissionState.launchPermissionRequest()
                }
            }
        }) {
            when (cameraPermissionState.status) {
                PermissionStatus.Granted -> {
                    Icon(
                        painter = painterResource(id = R.drawable.videocam_24dp),
                        tint = Color.White,
                        contentDescription = "",
                    )
                }

                is PermissionStatus.Denied -> {
                    Icon(
                        painter = painterResource(id = R.drawable.videocam_off24dp),
                        tint = Color.White,
                        contentDescription = "",
                    )
                }
            }
        }

        IconButton(onClick = {
            when (audioPermissionState.status) {
                PermissionStatus.Granted -> {
                }

                is PermissionStatus.Denied -> {
                    audioPermissionState.launchPermissionRequest()
                }
            }
        }) {
            when (audioPermissionState.status) {
                PermissionStatus.Granted -> {
                    Icon(
                        painter = painterResource(id = R.drawable.mic_24dp),
                        tint = Color.White,
                        contentDescription = "",
                    )
                }

                is PermissionStatus.Denied -> {
                    Icon(
                        painter = painterResource(id = R.drawable.mic_off24dp),
                        tint = Color.White,
                        contentDescription = "",
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Preview
@Composable
private fun PreviewCameraScreenDeviceControllerView() {
    CameraDeviceControllerView(
        onAudioPermissionStatus = {},
        onVideoPermissionStatus = {},
    )
}
