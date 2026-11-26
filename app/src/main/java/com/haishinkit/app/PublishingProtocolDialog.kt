package com.haishinkit.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal fun validProtocolUrl(
    value: String,
    protocol: PublishingProtocol,
): Boolean =
    runCatching {
        val uri = Uri.parse(value.trim()).normalizeScheme()
        when (protocol) {
            PublishingProtocol.RTMP -> uri.scheme in setOf("rtmp", "rtmps") && !uri.host.isNullOrBlank()
            PublishingProtocol.SRT -> uri.scheme == "srt" && uri.port in 1..65535 && (!uri.host.isNullOrBlank() || uri.getQueryParameter("mode") == "listener")
            PublishingProtocol.WEBRTC -> uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }
    }.getOrDefault(false)

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PublishingProtocolDialog(
    protocol: PublishingProtocol,
    onDismiss: () -> Unit,
    onApply: suspend (String, String, String) -> Result<Unit>,
) {
    val preference = Preference.shared
    var url by rememberSaveable(protocol) { mutableStateOf(if (PublishingProtocol.fromUrl(preference.streamURL) == protocol) preference.streamURL else preference.protocolURLs[protocol].orEmpty()) }
    var name by rememberSaveable { mutableStateOf(preference.streamName) }
    var whep by rememberSaveable { mutableStateOf(preference.whepURL) }
    var applying by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = validProtocolUrl(url, protocol) && (protocol != PublishingProtocol.WEBRTC || whep.isBlank() || validProtocolUrl(whep, PublishingProtocol.WEBRTC))
    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(protocol.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.studio_protocol_help))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = !applying,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text(if (protocol == PublishingProtocol.WEBRTC) "WHIP URL" else stringResource(R.string.studio_url)) },
                    isError = url.isNotBlank() && !validProtocolUrl(url, protocol),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (protocol == PublishingProtocol.RTMP) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !applying, singleLine = true, label = { Text(stringResource(R.string.studio_stream_name)) }, modifier = Modifier.fillMaxWidth())
                }
                if (protocol == PublishingProtocol.WEBRTC) {
                    Text(stringResource(R.string.studio_protocol_webrtc))
                    OutlinedTextField(value = whep, onValueChange = { whep = it }, enabled = !applying, singleLine = true, label = { Text("WHEP URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = whep.isNotBlank() && !validProtocolUrl(whep, PublishingProtocol.WEBRTC), modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !applying, onClick = {
                applying = true
                scope.launch {
                    try {
                        onApply(url, name, whep).onSuccess { onDismiss() }.onFailure { error = it.message ?: "Could not prepare this protocol." }
                    } finally {
                        applying = false
                    }
                }
            }) { Text(stringResource(R.string.studio_protocol_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !applying) { Text(stringResource(android.R.string.cancel)) } },
    )
}
