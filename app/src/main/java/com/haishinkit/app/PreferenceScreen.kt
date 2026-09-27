package com.haishinkit.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun PreferenceScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val keyboardController = LocalSoftwareKeyboardController.current
        var rtmpUrl by remember { mutableStateOf(Preference.shared.rtmpURL) }
        TextField(
            label = { Text("RTMP URL") },
            value = rtmpUrl,
            onValueChange = {
                rtmpUrl = it
                Preference.shared.rtmpURL = it
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(onDone = {
                    keyboardController?.hide()
                }),
            modifier =
                Modifier
                    .fillMaxWidth(),
        )
        var streamName by remember { mutableStateOf(Preference.shared.streamName) }
        TextField(
            label = { Text("RTMP StreamName") },
            value = streamName,
            onValueChange = {
                streamName = it
                Preference.shared.streamName = it
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(onDone = {
                    keyboardController?.hide()
                }),
            modifier =
                Modifier
                    .fillMaxWidth(),
        )
        if (Build.VERSION.SDK_INT >= 37) {
            val permission = rememberPermissionState(Manifest.permission.ACCESS_LOCAL_NETWORK)
            val context = LocalContext.current
            Text("Allow local network access before connecting to an RTMP server on your LAN.")
            Button(
                onClick = { permission.launchPermissionRequest() },
                enabled = !permission.status.isGranted,
            ) {
                Text(if (permission.status.isGranted) "Local network allowed" else "Allow local network")
            }
            TextButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }) {
                Text("App permission settings")
            }
        }
    }
}
