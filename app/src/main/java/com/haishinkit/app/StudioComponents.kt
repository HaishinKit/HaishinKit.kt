package com.haishinkit.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haishinkit.stream.StreamSession

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioSection(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioHeader(
    title: String,
    subtitle: String,
    onOpenSettings: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onOpenSettings?.let { TextButton(onClick = it) { Text(stringResource(R.string.studio_settings)) } }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StreamStatus(
    readyState: StreamSession.ReadyState,
    idleLabel: Int = R.string.studio_preview_only,
) {
    val label =
        when (readyState) {
            StreamSession.ReadyState.OPEN -> R.string.studio_connected
            StreamSession.ReadyState.CONNECTING -> R.string.studio_connecting
            StreamSession.ReadyState.CLOSING -> R.string.studio_stopping
            else -> idleLabel
        }
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioPreviewHeader(
    title: String,
    readyState: StreamSession.ReadyState,
    onOpenSettings: () -> Unit,
    idleLabel: Int = R.string.studio_preview_only,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StreamStatus(readyState, idleLabel)
        androidx.compose.material3.IconButton(onClick = onOpenSettings) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Settings, contentDescription = stringResource(R.string.studio_settings))
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioError(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f).padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            androidx.compose.material3.IconButton(onClick = onDismiss) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = stringResource(R.string.studio_dismiss_error))
            }
        }
    }
}
