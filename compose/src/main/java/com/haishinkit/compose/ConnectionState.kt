@file:Suppress("MemberVisibilityCanBePrivate")

package com.haishinkit.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haishinkit.event.Event
import com.haishinkit.event.IEventDispatcher
import com.haishinkit.event.IEventListener
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream

/*
* Create and [remember] a [ConnectionState] instance.
*/
@Composable
fun rememberConnectionState(factory: () -> RtmpConnection): ConnectionState =
    remember {
        ConnectionState(
            factory(),
        )
    }

@Suppress("UNUSED")
@Stable
class ConnectionState(
    private val connection: RtmpConnection,
) : IEventDispatcher {
    var isConnected by mutableStateOf(connection.isConnected)
        private set

    private val listener =
        object : IEventListener {
            override fun handleEvent(event: Event) {
                isConnected = connection.isConnected
            }
        }

    init {
        connection.addEventListener(Event.RTMP_STATUS, listener)
    }

    fun connect(
        command: String,
        vararg arguments: Any?,
    ) {
        connection.connect(command = command, arguments = arguments)
    }

    fun close() {
        connection.close()
        isConnected = false
    }

    fun createStream(context: Context): RtmpStream {
        return RtmpStream(context, connection)
    }

    fun dispose() {
        connection.removeEventListener(Event.RTMP_STATUS, listener)
        connection.dispose()
    }

    override fun addEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    ) {
        connection.addEventListener(type, listener, useCapture)
    }

    override fun dispatchEvent(event: Event) {
        connection.dispatchEvent(event)
    }

    override fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
        data: Any?,
    ) {
        connection.dispatchEventWith(type, bubbles, data)
    }

    override fun removeEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    ) {
        connection.removeEventListener(type, listener, useCapture)
    }
}
