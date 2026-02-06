package com.haishinkit.device

import android.content.Context
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages available camera devices on the system.
 *
 * This class observes both:
 * - Camera availability changes reported by [CameraManager]
 * - USB camera attach / detach events
 *
 * and keeps an up-to-date list of [CameraDevice] instances exposed via [deviceList].
 *
 * Call [release] when this manager is no longer needed to unregister callbacks
 * and receivers.
 */
@Suppress("UNUSED")
class CameraDeviceManager(
    private val context: Context,
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

    private val usbReceiver =
        UsbCameraReceiver {
            refresh()
        }

    private val _deviceList = MutableStateFlow<List<CameraDevice>>(emptyList())

    /**
     * A read-only [StateFlow] that emits the current list of available camera devices.
     */
    val deviceList: StateFlow<List<CameraDevice>> = _deviceList.asStateFlow()

    init {
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        context.registerReceiver(usbReceiver, filter)
        cameraManager.registerAvailabilityCallback(
            availabilityCallback,
            Handler(Looper.getMainLooper()),
        )
        refresh()
    }

    /**
     * Returns the current list of camera devices detected by the system.
     *
     * Each camera is mapped to a [CameraDevice] using its ID and lens facing
     * information obtained from [CameraCharacteristics].
     */
    fun getDeviceList(): List<CameraDevice> {
        return cameraManager.cameraIdList.map { cameraId ->
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val position =
                when (
                    chars.get(CameraCharacteristics.LENS_FACING)
                ) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraDevice.Position.FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraDevice.Position.BACK
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraDevice.Position.EXTERNAL
                    else -> CameraDevice.Position.UNSPECIFIED
                }
            CameraDevice(
                id = cameraId,
                name =
                    buildString {
                        append("${position.name.lowercase().replaceFirstChar { it.uppercase() }} Camera")
                        append(" ($cameraId)")
                    },
                position = position,
            )
        }
    }

    /**
     * Releases all registered callbacks and receivers.
     *
     * This method should be called to avoid memory leaks when the manager is no longer in use.
     */
    fun release() {
        cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        context.unregisterReceiver(usbReceiver)
    }

    private fun refresh() {
        _deviceList.value = getDeviceList()
    }
}
