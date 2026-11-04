package com.haishinkit.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class MediaProjectionBinding(private val context: Context) : ServiceConnection {
    var isStarted by mutableStateOf(false)
        private set
    var isStopping by mutableStateOf(false)
        private set

    private var isBound = false
    private var messenger: Messenger? = null
    private val replies =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                if (message.what == MediaProjectionService.MSG_CLOSED) {
                    dispose()
                    isStopping = false
                }
                true
            },
        )

    fun bind(intent: Intent) {
        if (isBound) return
        isBound = context.bindService(intent, this, 0)
        isStarted = isBound
    }

    fun stop() {
        if (!isBound) return
        isStopping = true
        messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CLOSE).apply { replyTo = replies })
    }

    fun dispose() {
        if (isBound) context.unbindService(this)
        isBound = false
        messenger = null
        isStarted = false
    }

    override fun onServiceConnected(
        name: ComponentName?,
        binder: IBinder?,
    ) {
        messenger = Messenger(binder)
        if (isStopping) stop() else messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CONNECT))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        dispose()
        isStopping = false
    }

    override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
}
