package com.haishinkit.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 720.dp).fillMaxSize()
                    .safeDrawingPadding().imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val keyboardController = LocalSoftwareKeyboardController.current
            StudioHeader(stringResource(R.string.studio_settings), stringResource(R.string.studio_settings_subtitle))
            var streamUrl by remember { mutableStateOf(Preference.shared.streamURL) }
            StudioSection(stringResource(R.string.studio_connection), stringResource(R.string.studio_connection_help)) {
                OutlinedTextField(
                    label = { Text(stringResource(R.string.studio_url)) },
                    singleLine = true,
                    supportingText = { Text(stringResource(if (streamUrl.isNotBlank() && !validStreamUrl(streamUrl)) R.string.studio_url_error else R.string.studio_url_help)) },
                    isError = streamUrl.isNotBlank() && !validStreamUrl(streamUrl),
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
                OutlinedTextField(
                    label = { Text(stringResource(R.string.studio_stream_name)) },
                    enabled = streamUrl.trim().substringBefore(':').lowercase() in setOf("rtmp", "rtmps"),
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
            }
            val rtc = streamUrl.trim().substringBefore(':').lowercase() in setOf("http", "https")
            if (rtc) {
                StudioSection(stringResource(R.string.studio_rtc), stringResource(R.string.studio_rtc_help)) {
                    var whepUrl by remember { mutableStateOf(Preference.shared.whepURL) }
                    OutlinedTextField(
                        label = { Text(stringResource(R.string.studio_whep)) },
                        isError = whepUrl.isNotBlank() && !validStreamUrl(whepUrl, webOnly = true),
                        supportingText = { if (whepUrl.isNotBlank() && !validStreamUrl(whepUrl, webOnly = true)) Text(stringResource(R.string.studio_whep_error)) },
                        value = whepUrl,
                        onValueChange = {
                            whepUrl = it
                            Preference.shared.whepURL = it
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var token by remember { mutableStateOf(Preference.shared.bearerToken) }
                    OutlinedTextField(
                        label = { Text(stringResource(R.string.studio_token)) },
                        value = token,
                        onValueChange = {
                            token = it
                            Preference.shared.bearerToken = it
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        supportingText = { Text(stringResource(R.string.studio_token_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    var iceServers by remember { mutableStateOf(Preference.shared.iceServers) }
                    OutlinedTextField(
                        label = { Text(stringResource(R.string.studio_ice)) },
                        value = iceServers,
                        onValueChange = {
                            iceServers = it
                            Preference.shared.iceServers = it
                        },
                        minLines = 2,
                        supportingText = { Text(stringResource(R.string.studio_ice_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val context = LocalContext.current

            val scope = rememberCoroutineScope()
            val currentRenderer = remember { RendererPreference.selected(context) }
            var selectedRenderer by remember { mutableStateOf(currentRenderer) }
            val vulkanAvailable = remember { RendererPreference.isVulkanAvailable() }
            var applyingRenderer by remember { mutableStateOf(false) }
            var rendererError by remember { mutableStateOf<String?>(null) }
            StudioSection(stringResource(R.string.studio_renderer), stringResource(R.string.studio_renderer_help)) {
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
                if (!vulkanAvailable) Text(stringResource(R.string.studio_renderer_unavailable))
                Text(stringResource(R.string.studio_renderer_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                ) { Text(stringResource(if (applyingRenderer) R.string.studio_applying else R.string.studio_apply)) }
                rendererError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (BuildConfig.VQMETRIX_AVAILABLE) {
                StudioSection(stringResource(R.string.studio_tools), stringResource(R.string.studio_tools_help)) {
                    val qualityToolContext = LocalContext.current
                    Button(onClick = {
                        qualityToolContext.startActivity(
                            Intent().setClassName(qualityToolContext, "com.haishinkit.app.vqmetrix.VQMetrixActivity"),
                        )
                    }) {
                        Text(stringResource(R.string.vq_open_tool))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 37) {
                StudioSection(stringResource(R.string.studio_network), stringResource(R.string.studio_network_help)) {
                    val permission = rememberPermissionState(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    val context = LocalContext.current

                    Button(
                        onClick = { permission.launchPermissionRequest() },
                        enabled = !permission.status.isGranted,
                    ) {
                        Text(stringResource(if (permission.status.isGranted) R.string.studio_network_allowed else R.string.studio_network_allow))
                    }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.studio_app_permissions))
                    }
                }
            }
        }
    }
}

internal fun validStreamUrl(
    value: String,
    webOnly: Boolean = false,
): Boolean {
    val uri = Uri.parse(value.trim()).normalizeScheme()
    val schemes = if (webOnly) setOf("http", "https") else setOf("rtmp", "rtmps", "srt", "http", "https")
    return uri.scheme in schemes && (uri.scheme == "srt" || !uri.host.isNullOrBlank())
}
