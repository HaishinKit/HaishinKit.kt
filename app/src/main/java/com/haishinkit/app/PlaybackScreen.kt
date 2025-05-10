package com.haishinkit.app

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.haishinkit.compose.HaishinKitView
import com.haishinkit.compose.rememberConnectionState
import com.haishinkit.event.Event
import com.haishinkit.event.EventUtils
import com.haishinkit.event.IEventListener
import com.haishinkit.rtmp.RtmpConnection

private const val TAG = "PlaybackScreen"

@Suppress("ktlint:standard:function-naming")
@Composable
fun PlaybackScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val connectionState =
        rememberConnectionState {
            RtmpConnection()
        }

    val stream =
        remember(connectionState) {
            connectionState.createStream(context)
        }

    DisposableEffect(Unit) {
        onDispose {
            connectionState.dispose()
        }
    }

    LaunchedEffect(Unit) {
        connectionState.addEventListener(
            Event.RTMP_STATUS,
            object : IEventListener {
                override fun handleEvent(event: Event) {
                    val data = EventUtils.toMap(event)
                    Log.i(TAG, data.toString())
                    when (data["code"]) {
                        RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                            stream.play(Preference.shared.streamName)
                        }

                        else -> {
                        }
                    }
                }
            },
        )
    }

    HaishinKitView(
        stream = stream,
        modifier = Modifier.fillMaxSize(),
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(8.dp),
    ) {
        Button(
            modifier =
                Modifier.align(Alignment.BottomEnd),
            onClick = {
                if (connectionState.isConnected) {
                    connectionState.close()
                } else {
                    connectionState.connect(Preference.shared.rtmpURL)
                }
            },
        ) {
            if (connectionState.isConnected) {
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
