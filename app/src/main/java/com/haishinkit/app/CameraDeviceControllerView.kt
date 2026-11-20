package com.haishinkit.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
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
    val context = LocalContext.current
    val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(audioPermission) {
        snapshotFlow { audioPermission.status }.collect { onAudioPermissionStatus(audioPermission) }
    }
    LaunchedEffect(cameraPermission) {
        snapshotFlow { cameraPermission.status }.collect { onVideoPermissionStatus(cameraPermission) }
    }
    val cameraGranted = cameraPermission.status.isGranted
    val audioGranted = audioPermission.status.isGranted
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { cameraPermission.launchPermissionRequest() }, enabled = !cameraGranted) {
                Icon(
                    painterResource(if (cameraGranted) R.drawable.videocam_24dp else R.drawable.videocam_off24dp),
                    contentDescription = stringResource(if (cameraGranted) R.string.studio_camera_allowed else R.string.studio_camera_allow),
                )
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, enabled = cameraGranted && cameras.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(camera?.name ?: stringResource(R.string.studio_camera_select), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    cameras.forEach { device ->
                        DropdownMenuItem(text = { Text(device.name) }, onClick = {
                            onCameraSelected(device)
                            expanded = false
                        })
                    }
                }
            }
            IconButton(onClick = { audioPermission.launchPermissionRequest() }, enabled = !audioGranted) {
                Icon(
                    painterResource(if (audioGranted) R.drawable.mic_24dp else R.drawable.mic_off24dp),
                    contentDescription = stringResource(if (audioGranted) R.string.studio_mic_allowed else R.string.studio_mic_allow),
                )
            }
        }
        if (!cameraGranted || !audioGranted) {
            Text(stringResource(R.string.studio_permission_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text(stringResource(R.string.studio_app_permissions)) }
        }
    }
}
