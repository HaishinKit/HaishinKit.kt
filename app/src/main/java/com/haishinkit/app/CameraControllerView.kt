package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haishinkit.stream.StreamSession

@Suppress("ktlint:standard:function-naming")
@Composable
fun CameraControllerView(
    isRecording: Boolean,
    isConnected: Boolean,
    onClickScreenShot: () -> Unit,
    onClickConnect: () -> Unit,
    onClickRecording: () -> Unit,
    readyState: StreamSession.ReadyState = if (isConnected) StreamSession.ReadyState.OPEN else StreamSession.ReadyState.CLOSED,
    canCancelConnection: Boolean = false,
    controlsEnabled: Boolean = true,
    mediaToolsEnabled: Boolean = true,
) {
    val connecting = readyState == StreamSession.ReadyState.CONNECTING
    val closing = readyState == StreamSession.ReadyState.CLOSING
    val active = readyState == StreamSession.ReadyState.OPEN
    val label =
        stringResource(
            when {
                closing -> R.string.studio_stopping
                connecting && canCancelConnection -> R.string.studio_cancel
                connecting -> R.string.studio_connecting
                active -> R.string.studio_stop
                else -> R.string.studio_go_live
            },
        )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PublishAction(
            stringResource(R.string.studio_snapshot),
            onClickScreenShot,
            Modifier.weight(1f),
            enabled = controlsEnabled && mediaToolsEnabled,
        ) { Icon(painterResource(R.drawable.studio_snapshot), contentDescription = null) }
        val enabled = controlsEnabled && !closing && (!connecting || canCancelConnection)
        PublishAction(
            label,
            onClickConnect,
            Modifier.weight(1.25f),
            enabled = enabled,
            primary = true,
            containerColor =
                if (!enabled) {
                    PreviewColors.container
                } else if (active) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            contentColor =
                if (!enabled) {
                    PreviewColors.content
                } else if (active) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
        ) {
            if (connecting || closing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = if (enabled) MaterialTheme.colorScheme.onPrimary else PreviewColors.content)
            } else {
                Icon(if (active) Icons.Filled.Close else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        PublishAction(
            stringResource(if (isRecording) R.string.studio_record_stop else R.string.studio_record),
            onClickRecording,
            Modifier.weight(1f),
            enabled = controlsEnabled && mediaToolsEnabled,
        ) {
            Icon(painterResource(if (isRecording) R.drawable.studio_stop else R.drawable.studio_record), contentDescription = null, tint = PreviewColors.content)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun PublishAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    containerColor: Color = PreviewColors.control,
    contentColor: Color = PreviewColors.content,
    icon: @Composable () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = PreviewColors.content, disabledContentColor = PreviewColors.content.copy(alpha = 0.6f)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = CircleShape, color = containerColor, contentColor = contentColor, modifier = Modifier.size(if (primary) 72.dp else 48.dp)) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { icon() }
            }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}
