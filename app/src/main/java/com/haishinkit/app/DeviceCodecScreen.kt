package com.haishinkit.app

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DeviceCodecScreen(onBack: () -> Unit) {
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val catalog by produceState<Result<List<DeviceCodecFamily>>?>(null, attempt) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { DeviceCodecCatalog.read() } }
    }
    DeviceCodecContent(catalog, onBack, onRetry = { attempt++ })
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DeviceCodecContent(
    catalog: Result<List<DeviceCodecFamily>>?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var video by rememberSaveable { mutableStateOf(true) }
    val videoScroll = rememberLazyListState()
    val audioScroll = rememberLazyListState()
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.studio_codecs_back)) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.studio_codecs_title), style = MaterialTheme.typography.titleLarge)
                    Text(Build.MODEL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(R.string.studio_codecs_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = video, onClick = { video = true }, label = { Text(stringResource(R.string.studio_codecs_video)) })
                FilterChip(selected = !video, onClick = { video = false }, label = { Text(stringResource(R.string.studio_codecs_audio)) })
            }
            when {
                catalog == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                catalog.isFailure ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.studio_codecs_error))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.studio_codecs_retry)) }
                    }
                else ->
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = if (video) videoScroll else audioScroll,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(catalog.getOrThrow().filter { it.mime.startsWith(if (video) "video/" else "audio/") }, key = { it.mime }) { family ->
                            DeviceCodecCard(family)
                        }
                    }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun DeviceCodecCard(family: DeviceCodecFamily) {
    var expanded by rememberSaveable(family.mime) { mutableStateOf(false) }
    StudioSection(family.label) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CodecDirection(stringResource(R.string.studio_codecs_encode), family.encoders, expanded, Modifier.weight(1f))
            CodecDirection(stringResource(R.string.studio_codecs_decode), family.decoders, expanded, Modifier.weight(1f))
        }
        if (expanded) Text(family.mime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.studio_codecs_hide_details else R.string.studio_codecs_details))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CodecDirection(
    label: String,
    implementations: List<DeviceCodecImplementation>,
    expanded: Boolean,
    modifier: Modifier,
) {
    val hardware = implementations.any { it.acceleration == CodecAcceleration.HARDWARE }
    val software = implementations.any { it.acceleration == CodecAcceleration.SOFTWARE }
    val status =
        when {
            implementations.isEmpty() -> R.string.studio_codecs_unsupported
            hardware && software -> R.string.studio_codecs_hardware_software
            hardware -> R.string.studio_codecs_hardware
            software -> R.string.studio_codecs_software
            else -> R.string.studio_codecs_available
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(status), style = MaterialTheme.typography.bodyMedium, color = if (implementations.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        if (expanded) implementations.forEach { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
