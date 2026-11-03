package com.haishinkit.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
    LaunchedEffect(Unit) { viewModel.refreshSession() }
    val session =
        rememberStreamSessionState(viewModel.session)
    val readyState by session.readyState.collectAsState()
    val active = readyState != StreamSession.ReadyState.CLOSED

    HaishinKitView(
        stream = session.stream,
        modifier = Modifier.fillMaxSize(),
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(8.dp),
    ) {
        Button(
            modifier =
                Modifier.align(Alignment.BottomEnd),
            onClick = {
                scope.launch {
                    if (active) {
                        session.close()
                    } else {
                        session.connect()
                    }
                }
            },
        ) {
            if (active) {
                Text("STOP")
            } else {
                Text("PLAY")
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Preview
@Composable
private fun PreviewPlaybackScreen() {
    PlaybackScreen(modifier = Modifier.fillMaxSize())
}
