package com.haishinkit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberStreamSessionState
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.launch

@Suppress("unused")
private const val TAG = "PlaybackScreen"

@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaybackScreen(
    viewModel: PlaybackViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var errorMessage by remember(viewModel.session) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshSession() }
    val session =
        rememberStreamSessionState(viewModel.session)
    val readyState by session.readyState.collectAsState()
    val active = readyState != StreamSession.ReadyState.CLOSED

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        HaishinKitView(
            stream = session.stream,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        ) {
            errorMessage?.let { message ->
                Column(
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Can't connect to stream", color = Color.White)
                    Text(message, color = Color.White)
                    Button(onClick = { errorMessage = null }) { Text("Try Again") }
                }
            }
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                StreamControlButton(
                    readyState = readyState,
                    startLabel = "PLAY",
                    onClick = {
                        errorMessage = null
                        scope.launch {
                            if (active) {
                                session.close()
                            } else {
                                session.connect().onFailure {
                                    errorMessage = it.message ?: "Check the stream URL and your network connection."
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
