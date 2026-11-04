package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.haishinkit.stream.StreamSession

@Suppress("ktlint:standard:function-naming")
@Composable
fun StreamControlButton(
    readyState: StreamSession.ReadyState,
    startLabel: String,
    onClick: () -> Unit,
    canCancelConnection: Boolean = false,
) {
    val connecting = readyState == StreamSession.ReadyState.CONNECTING
    val closing = readyState == StreamSession.ReadyState.CLOSING
    Button(
        onClick = onClick,
        enabled = !closing && (!connecting || canCancelConnection),
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (readyState == StreamSession.ReadyState.OPEN) Color(0xFFE53935) else Color(0xFF1976D2),
                contentColor = Color.White,
                disabledContainerColor = Color.Black.copy(alpha = 0.5f),
            ),
        modifier =
            Modifier.size(80.dp).semantics {
                if (connecting && canCancelConnection) contentDescription = "Cancel connection"
            },
    ) {
        if (connecting && canCancelConnection) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Text("STOP")
            }
        } else if (connecting || closing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(if (readyState == StreamSession.ReadyState.OPEN) "STOP" else startLabel)
        }
    }
}
