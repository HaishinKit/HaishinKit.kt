package com.haishinkit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.stream.Stream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.flow.StateFlow

/**
 * Creates and remembers a [StreamSessionState] for [session]. A different session creates new state.
 */
@Composable
fun rememberStreamSessionState(session: StreamSession): StreamSessionState =
    remember(session) {
        StreamSessionState(
            session,
        )
    }

/**
 * Wraps a stream session with Compose-observable connection state.
 *
 * [isConnected] is refreshed after a successful [connect] and after [close] returns.
 * Observe [readyState] for asynchronous state changes from the underlying session.
 *
 * @property session The underlying session. Use this wrapper's controls to refresh its Compose state.
 */
@Stable
class StreamSessionState(
    val session: StreamSession,
) : StreamSession {
    override var isConnected by mutableStateOf(session.isConnected)
        private set

    override val stream: Stream = session.stream

    override val readyState: StateFlow<StreamSession.ReadyState> = session.readyState

    override suspend fun connect(): Result<Unit> =
        session.connect().onSuccess {
            isConnected = session.isConnected
        }

    override suspend fun close(): Result<Unit> {
        val result = session.close()
        isConnected = session.isConnected
        return result
    }
}
