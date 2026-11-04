package com.haishinkit.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Suppress("ktlint:standard:function-naming")
@Composable
fun PreferenceScreen(
    modifier: Modifier = Modifier,
    onApplyRenderer: suspend (Renderer) -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val keyboardController = LocalSoftwareKeyboardController.current
        var streamUrl by remember { mutableStateOf(Preference.shared.streamURL) }
        TextField(
            label = { Text("Stream URL (RTMP / SRT)") },
            value = streamUrl,
            onValueChange = {
                streamUrl = it
                Preference.shared.streamURL = it
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
            enabled = !streamUrl.startsWith("srt:"),
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
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val currentRenderer = remember { RendererPreference.selected(context) }
        var selectedRenderer by remember { mutableStateOf(currentRenderer) }
        val vulkanAvailable = remember { RendererPreference.isVulkanAvailable() }
        var applyingRenderer by remember { mutableStateOf(false) }
        var rendererError by remember { mutableStateOf<String?>(null) }
        Text("Renderer", style = MaterialTheme.typography.titleMedium)
        Renderer.entries.forEach { renderer ->
            val enabled = !applyingRenderer && (renderer != Renderer.VULKAN || vulkanAvailable)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().selectable(
                        selected = selectedRenderer == renderer,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { selectedRenderer = renderer },
                    ),
            ) {
                RadioButton(
                    selected = selectedRenderer == renderer,
                    enabled = enabled,
                    onClick = null,
                )
                Text(renderer.label)
            }
        }
        if (!vulkanAvailable) Text("Vulkan is not available on this device. OpenGL will be used.")
        Text("Applying stops streaming, playback, recording, and screen sharing, then reopens the app screens.")
        Button(
            enabled = !applyingRenderer && selectedRenderer != currentRenderer && (selectedRenderer != Renderer.VULKAN || vulkanAvailable),
            onClick = {
                applyingRenderer = true
                rendererError = null
                scope.launch {
                    try {
                        onApplyRenderer(selectedRenderer)
                    } catch (error: Exception) {
                        rendererError = error.message ?: "Could not change the renderer."
                    } finally {
                        applyingRenderer = false
                    }
                }
            },
        ) { Text(if (applyingRenderer) "Applying…" else "Apply renderer") }
        rendererError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (Build.VERSION.SDK_INT >= 37) {
            val permission = rememberPermissionState(Manifest.permission.ACCESS_LOCAL_NETWORK)
            val context = LocalContext.current
            Text("Allow local network access before connecting to a stream on your LAN.")
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
