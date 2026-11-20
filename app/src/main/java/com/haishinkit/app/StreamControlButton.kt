package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.haishinkit.stream.StreamSession

@Suppress("ktlint:standard:function-naming")
@Composable
fun StreamControlButton(
    readyState: StreamSession.ReadyState,
    startLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    canCancelConnection: Boolean = false,
) {
    val connecting = readyState == StreamSession.ReadyState.CONNECTING
    val closing = readyState == StreamSession.ReadyState.CLOSING
    val active = readyState == StreamSession.ReadyState.OPEN
    val label =
        when {
            closing -> stringResource(R.string.studio_stopping)
            connecting && canCancelConnection -> stringResource(R.string.studio_cancel)
            connecting -> stringResource(R.string.studio_connecting)
            active -> stringResource(R.string.studio_stop)
            else -> startLabel
        }
    Button(
        onClick = onClick,
        enabled = !closing && (!connecting || canCancelConnection),
        modifier = modifier.defaultMinSize(minHeight = 56.dp),
        shape = MaterialTheme.shapes.medium,
        colors =
            if (active) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            } else {
                ButtonDefaults.buttonColors()
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connecting || closing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            } else {
                Icon(if (active) Icons.Filled.Close else Icons.Filled.PlayArrow, contentDescription = null)
            }
            Text(label)
        }
    }
}
