package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PublishEncodingControls(
    settings: PublishEncodingSettings,
    protocol: PublishingProtocol,
    publishing: Boolean,
    onUpdate: (PublishEncodingSettings) -> Result<Unit>,
) {
    var editError by remember(protocol) { mutableStateOf<String?>(null) }
    var errorField by remember(protocol) { mutableStateOf<String?>(null) }
    val fallbackError = stringResource(R.string.studio_encoding_error)

    fun update(
        next: PublishEncodingSettings,
        field: String,
    ): Boolean {
        val result = onUpdate(next)
        editError = result.exceptionOrNull()?.let { it.message ?: fallbackError }
        errorField = if (result.isFailure) field else null
        return result.isSuccess
    }
    val encoders by produceState<Result<Set<String>>?>(null) {
        value =
            withContext(Dispatchers.Default) {
                runCatching { DeviceCodecCatalog.read().filter { it.encoders.isNotEmpty() }.map { it.mime }.toSet() }
            }
    }
    val available = encoders?.getOrNull().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.studio_encoding_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (protocol == PublishingProtocol.RTMP) R.string.studio_encoding_help else R.string.studio_encoding_transport_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (publishing) Text(stringResource(R.string.studio_encoding_locked), style = MaterialTheme.typography.bodySmall)
        if (encoders == null) Text(stringResource(R.string.studio_encoding_loading))
        if (encoders?.isFailure == true) Text(stringResource(R.string.studio_encoding_error), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.studio_encoding_video), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PublishVideoCodec.entries.filter { codec -> protocol == PublishingProtocol.RTMP || codec == PublishVideoCodec.H264 || (protocol == PublishingProtocol.SRT && codec == PublishVideoCodec.H265) }.forEach { codec ->
                val supported = codec.profile.mime in available
                FilterChip(
                    selected = settings.videoCodec == codec,
                    enabled = !publishing && supported,
                    onClick = { update(settings.copy(videoCodec = codec), "video") },
                    label = { Text(if (supported || encoders == null) codec.label else stringResource(R.string.studio_encoding_unavailable, codec.label)) },
                )
            }
        }
        if (errorField == "video") editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.studio_encoding_audio), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PublishAudioCodec.entries.filter { protocol == PublishingProtocol.RTMP || it == if (protocol == PublishingProtocol.SRT) PublishAudioCodec.AAC else PublishAudioCodec.OPUS }.forEach { codec ->
                val supported = codec.mime in available
                FilterChip(
                    selected = settings.audioCodec == codec,
                    enabled = !publishing && supported,
                    onClick = { update(settings.copy(audioCodec = codec), "audio") },
                    label = { Text(if (supported || encoders == null) codec.label else stringResource(R.string.studio_encoding_unavailable, codec.label)) },
                )
            }
        }
        if (errorField == "audio") editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        var bitRate by rememberSaveable(settings.videoBitRate) { mutableFloatStateOf(settings.videoBitRate / 1000f) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.studio_encoding_bitrate), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.studio_encoding_kbps, bitRate.roundToInt()), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = bitRate,
            onValueChange = { bitRate = it },
            onValueChangeFinished = {
                if (!update(settings.copy(videoBitRate = bitRate.roundToInt() * 1000), "bitrate")) bitRate = settings.videoBitRate / 1000f
            },
            valueRange = PublishEncodingSettings.MIN_VIDEO_BIT_RATE / 1000f..PublishEncodingSettings.MAX_VIDEO_BIT_RATE / 1000f,
        )
        if (errorField == "bitrate") editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.studio_encoding_bitrate_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
