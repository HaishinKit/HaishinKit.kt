package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StreamControlButton(
                readyState = readyState,
                startLabel = stringResource(R.string.studio_go_live),
                onClick = onClickConnect,
                canCancelConnection = canCancelConnection,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClickScreenShot, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(R.string.studio_snapshot))
                }
                OutlinedButton(onClick = onClickRecording, modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(if (isRecording) R.string.studio_record_stop else R.string.studio_record))
                }
            }
        }
    }
}
