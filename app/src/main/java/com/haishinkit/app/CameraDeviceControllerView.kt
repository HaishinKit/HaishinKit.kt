package com.haishinkit.app

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.haishinkit.device.CameraDevice

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraDeviceControllerView(
    camera: CameraDevice?,
    cameras: List<CameraDevice>,
    onCameraSelected: (device: CameraDevice) -> Unit,
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
                        contentDescription = null,
                    )
                }

                is PermissionStatus.Denied -> {
                    Icon(
                        painter = painterResource(id = R.drawable.videocam_off24dp),
                        tint = Color.White,
                        contentDescription = null,
                    )
                }
            }
        }

        var expanded by remember { mutableStateOf(false) }
        Box {
            Button(onClick = { expanded = true }) {
                camera?.name?.let { Text(it) }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {
                cameras.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = {
                            Text(text = selectionOption.name)
                        },
                        onClick = {
                            onCameraSelected(selectionOption)
                            expanded = false
                        },
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
                        contentDescription = null,
                    )
                }

                is PermissionStatus.Denied -> {
                    Icon(
                        painter = painterResource(id = R.drawable.mic_off24dp),
                        tint = Color.White,
                        contentDescription = null,
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
        camera = null,
        cameras = emptyList(),
        onCameraSelected = {},
        onAudioPermissionStatus = {},
        onVideoPermissionStatus = {},
    )
}
