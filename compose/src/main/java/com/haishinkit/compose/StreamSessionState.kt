package com.haishinkit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession

/*
* Create and [remember] a [StreamSessionState] instance.
*/
@Composable
fun rememberStreamSessionState(session: StreamSession): StreamSessionState =
    remember {
        StreamSessionState(
            session,
        )
    }

@Stable
class StreamSessionState(
    val session: StreamSession,
) : StreamSession {
    override var isConnected by mutableStateOf(session.isConnected)
        private set

    override val stream: Stream = session.stream

    override suspend fun connect(method: StreamSession.Method): Result<Unit> =
        session.connect(method).onSuccess {
            isConnected = session.isConnected
        }

    override suspend fun close(): Result<Unit> {
        val result = session.close()
        isConnected = session.isConnected
        return result
    }
}
