package com.haishinkit.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberStreamSessionState
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.launch

private const val TAG = "PlaybackScreen"

@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaybackScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session =
        rememberStreamSessionState(
            StreamSession
                .Builder(
                    context,
                    Preference.shared.rtmpURL.toUri(),
                )
                .setMode(StreamSession.Mode.PLAYBACK)
                .build(),
        )

    DisposableEffect(Unit) {
        onDispose {
            // session.dispose()
        }
    }

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
                    if (session.isConnected) {
                        session.close()
                    } else {
                        session.connect()
                    }
                }
            },
        ) {
            if (session.isConnected) {
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
