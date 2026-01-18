package com.haishinkit.device

import android.content.Context
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("UNUSED")
class DeviceManager(
    private val context: Context
) {
    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val availabilityCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                refresh()
            }

            override fun onCameraUnavailable(cameraId: String) {
                refresh()
            }
        }

    private val usbReceiver = UsbCameraReceiver {
        refresh()
    }

    private val _cameraList = MutableStateFlow<List<CameraDevice>>(emptyList())
    val cameraList: StateFlow<List<CameraDevice>> = _cameraList.asStateFlow()

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
        cameraManager.registerAvailabilityCallback(
            availabilityCallback,
            Handler(Looper.getMainLooper())
        )
        refresh()
    }

    fun getCameraList(): List<CameraDevice> {
        return cameraManager.cameraIdList.map { cameraId ->
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val position = when (
                chars.get(CameraCharacteristics.LENS_FACING)
            ) {
                CameraCharacteristics.LENS_FACING_FRONT -> CameraDevice.Position.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> CameraDevice.Position.BACK
                CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraDevice.Position.EXTERNAL
                else -> CameraDevice.Position.UNSPECIFIED
            }
            CameraDevice(
                id = cameraId,
                name = buildString {
                    append("${position.name.lowercase().replaceFirstChar { it.uppercase() }} Camera")
                    append(" ($cameraId)")
                },
                position = position
            )
        }
    }

    fun release() {
        cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        context.unregisterReceiver(usbReceiver)
    }

    private fun refresh() {
        _cameraList.value = getCameraList()
    }
}