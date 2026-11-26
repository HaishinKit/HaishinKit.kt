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
import androidx.compose.runtime.produceState
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
internal fun RtmpEncodingControls(
    settings: RtmpEncodingSettings,
    publishing: Boolean,
    onUpdate: (RtmpEncodingSettings) -> Boolean,
) {
    val encoders by produceState<Result<Set<String>>?>(null) {
        value =
            withContext(Dispatchers.Default) {
                runCatching { DeviceCodecCatalog.read().filter { it.encoders.isNotEmpty() }.map { it.mime }.toSet() }
            }
    }
    val available = encoders?.getOrNull().orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.studio_encoding_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.studio_encoding_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (publishing) Text(stringResource(R.string.studio_encoding_locked), style = MaterialTheme.typography.bodySmall)
        if (encoders == null) Text(stringResource(R.string.studio_encoding_loading))
        if (encoders?.isFailure == true) Text(stringResource(R.string.studio_encoding_error), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.studio_encoding_video), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RtmpVideoCodec.entries.forEach { codec ->
                val supported = codec.profile.mime in available
                FilterChip(
                    selected = settings.videoCodec == codec,
                    enabled = !publishing && supported,
                    onClick = { onUpdate(settings.copy(videoCodec = codec)) },
                    label = { Text(if (supported || encoders == null) codec.label else stringResource(R.string.studio_encoding_unavailable, codec.label)) },
                )
            }
        }
        Text(stringResource(R.string.studio_encoding_audio), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RtmpAudioCodec.entries.forEach { codec ->
                val supported = codec.mime in available
                FilterChip(
                    selected = settings.audioCodec == codec,
                    enabled = !publishing && supported,
                    onClick = { onUpdate(settings.copy(audioCodec = codec)) },
                    label = { Text(if (supported || encoders == null) codec.label else stringResource(R.string.studio_encoding_unavailable, codec.label)) },
                )
            }
        }
        var bitRate by rememberSaveable(settings.videoBitRate) { mutableFloatStateOf(settings.videoBitRate / 1000f) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.studio_encoding_bitrate), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.studio_encoding_kbps, bitRate.roundToInt()), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = bitRate,
            onValueChange = { bitRate = it },
            onValueChangeFinished = {
                if (!onUpdate(settings.copy(videoBitRate = bitRate.roundToInt() * 1000))) bitRate = settings.videoBitRate / 1000f
            },
            valueRange = RtmpEncodingSettings.MIN_VIDEO_BIT_RATE / 1000f..RtmpEncodingSettings.MAX_VIDEO_BIT_RATE / 1000f,
        )
        Text(stringResource(R.string.studio_encoding_bitrate_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
