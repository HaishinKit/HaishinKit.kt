package com.haishinkit.app

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.haishinkit.graphics.DynamicRangeMode
import kotlinx.coroutines.launch

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun HdrSettingsControls(onApply: suspend (HdrSettings) -> Unit) {
    val context = LocalContext.current
    val current = remember { HdrPreference.selected(context) }
    val settingsSaver =
        remember {
            listSaver<HdrSettings, String>(
                save = { listOf(it.publishing.name, it.playback.name) },
                restore = { HdrSettings(DynamicRangeMode.valueOf(it[0]), DynamicRangeMode.valueOf(it[1])) },
            )
        }
    var selected by rememberSaveable(stateSaver = settingsSaver) { mutableStateOf(current) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hlgAvailable = Build.VERSION.SDK_INT >= 33 && PublishingProtocol.fromUrl(Preference.shared.streamURL) == PublishingProtocol.RTMP
    StudioSection(stringResource(R.string.studio_hdr_title), stringResource(R.string.studio_hdr_help)) {
        Text(stringResource(R.string.studio_hdr_publish_label), style = MaterialTheme.typography.labelLarge)
        HdrRangeChips("publish", selected.publishing, applying, hlgAvailable) { selected = selected.copy(publishing = it) }
        Text(stringResource(R.string.studio_playback), style = MaterialTheme.typography.labelLarge)
        HdrRangeChips("playback", selected.playback, applying, hlgAvailable) { selected = selected.copy(playback = it) }
        Text(stringResource(R.string.studio_hdr_restart), style = MaterialTheme.typography.bodySmall)
        if (!hlgAvailable) Text(stringResource(if (Build.VERSION.SDK_INT < 33) R.string.studio_hdr_android else R.string.studio_hdr_rtmp_only))
        Button(
            modifier = Modifier.testTag("hdr-apply"),
            enabled = !applying && selected != current,
            onClick = {
                applying = true
                error = null
                scope.launch {
                    try {
                        onApply(selected)
                    } catch (cause: kotlinx.coroutines.CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        error = cause.message ?: context.getString(R.string.studio_encoding_error)
                    } finally {
                        applying = false
                    }
                }
            },
        ) { Text(stringResource(if (applying) R.string.studio_applying else R.string.studio_hdr_apply)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun HdrRangeChips(
    direction: String,
    range: DynamicRangeMode,
    applying: Boolean,
    hlgAvailable: Boolean,
    onSelect: (DynamicRangeMode) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DynamicRangeMode.entries.forEach { choice ->
            FilterChip(
                modifier = Modifier.testTag("hdr-$direction-${choice.name}"),
                selected = range == choice,
                enabled = !applying && (choice == DynamicRangeMode.SDR || hlgAvailable),
                onClick = { onSelect(choice) },
                label = { Text(if (choice == DynamicRangeMode.SDR) "SDR" else "HLG · HDR") },
            )
        }
    }
}
