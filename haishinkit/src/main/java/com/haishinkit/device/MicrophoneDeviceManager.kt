package com.haishinkit.device

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("UNUSED")
@RequiresApi(Build.VERSION_CODES.M)
class MicrophoneDeviceManager(
    context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo?>?) {
                refresh()
                devices?.forEach {
                    when (it?.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        -> {
                            _headsetConnected.value = true
                        }
                    }
                }
            }

            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo?>?) {
                refresh()
                devices?.forEach {
                    when (it?.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        -> {
                            _headsetConnected.value = false
                        }
                    }
                }
            }
        }

    var hasHeadset: Boolean = false
        private set
    private val _deviceList = MutableStateFlow<List<MicrophoneDevice>>(emptyList())
    val deviceList: StateFlow<List<MicrophoneDevice>> = _deviceList.asStateFlow()

    private val _headsetConnected = MutableStateFlow(false)
    var headsetConnected: StateFlow<Boolean> = _headsetConnected.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(
            callback,
            Handler(Looper.getMainLooper()),
        )
        refresh()
    }

    fun getDeviceList(): List<MicrophoneDevice> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { info ->
            val address = info.address
            val productName = info.productName?.toString() ?: "Unknown"
            when (info.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                    MicrophoneDevice(productName, address, MicrophoneDevice.Category.BUILTIN)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    MicrophoneDevice(productName, address, MicrophoneDevice.Category.BLUETOOTH)
                AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    MicrophoneDevice(productName, address, MicrophoneDevice.Category.HEADSET)
                else ->
                    MicrophoneDevice(productName, address, MicrophoneDevice.Category.OTHERS)
            }
        }
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun refresh() {
        _deviceList.value = getDeviceList()
        hasHeadset =
            _deviceList.value.any { device ->
                device.category != MicrophoneDevice.Category.BUILTIN
            }
        Log.d("TAG", hasHeadset.toString())
    }
}
