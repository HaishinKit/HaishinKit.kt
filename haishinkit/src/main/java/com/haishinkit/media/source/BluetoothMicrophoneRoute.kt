package com.haishinkit.media.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/** Owns only the communication route requested by an opted-in recording source. */
@Suppress("DEPRECATION")
internal class BluetoothMicrophoneRoute(private val context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var opened = false
    private var previousMode: Int? = null
    private var selectedDeviceId: Int? = null
    private var scoRequested = false
    private var receiverRegistered = false
    private val scoReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                val previous = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED &&
                    previous in listOf(AudioManager.SCO_AUDIO_STATE_CONNECTING, AudioManager.SCO_AUDIO_STATE_CONNECTED)
                ) {
                    synchronized(this@BluetoothMicrophoneRoute) {
                        if (opened && scoRequested) releaseRoute()
                    }
                }
            }
        }
    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = update()

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = update()
        }

    @Synchronized
    fun open() {
        if (opened) return
        opened = true
        try {
            if (Build.VERSION.SDK_INT < 31) {
                ContextCompat.registerReceiver(context, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            update()
        } catch (cause: RuntimeException) {
            close()
            throw cause
        }
    }

    @Synchronized
    fun close() {
        if (!opened) return
        opened = false
        try {
            manager.unregisterAudioDeviceCallback(callback)
        } finally {
            try {
                if (receiverRegistered) context.unregisterReceiver(scoReceiver)
            } finally {
                receiverRegistered = false
                releaseRoute()
            }
        }
    }

    @Synchronized
    private fun update() {
        if (!opened) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = manager.availableCommunicationDevices.filter { isBluetoothMicrophone(it.type) }
                val device = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()
                if (device == null) {
                    releaseRoute()
                } else if (device.id != selectedDeviceId) {
                    acquireMode()
                    if (manager.setCommunicationDevice(device)) {
                        selectedDeviceId = device.id
                    } else {
                        releaseRoute()
                    }
                }
            } else {
                val available = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (available && !scoRequested) {
                    acquireMode()
                    // Keep the recorder alive while the asynchronous SCO connection is established.
                    scoRequested = true
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                } else if (!available) {
                    releaseRoute()
                }
            }
        } catch (cause: RuntimeException) {
            Log.w(TAG, "Unable to select Bluetooth microphone; using system routing", cause)
            releaseRoute()
        }
    }

    private fun acquireMode() {
        if (previousMode != null) return
        previousMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun releaseRoute() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && selectedDeviceId != null) {
                manager.clearCommunicationDevice()
            }
            if (scoRequested) {
                manager.stopBluetoothSco()
                manager.isBluetoothScoOn = false
            }
        } catch (cause: RuntimeException) {
            Log.w(TAG, "Unable to release Bluetooth route", cause)
        } finally {
            selectedDeviceId = null
            scoRequested = false
            previousMode?.let { mode ->
                runCatching {
                    if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = mode
                }.onFailure { Log.w(TAG, "Unable to restore audio mode", it) }
            }
            previousMode = null
        }
    }

    companion object {
        private const val TAG = "BluetoothMicrophone"

        internal fun isBluetoothMicrophone(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
