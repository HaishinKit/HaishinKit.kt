package com.haishinkit.media.source

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class BluetoothMicrophoneRouteTest {
    @Test fun aRejectedRouteRestoresModeAndDoesNotLeaveARequest() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        shadowOf(manager).setAvailableCommunicationDevices(listOf(headset))
        shadowOf(manager).lockCommunicationDevice(true)
        manager.mode = AudioManager.MODE_NORMAL
        val route = BluetoothMicrophoneRoute(context)
        try {
            route.open()
            assertNull(manager.communicationDevice)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        } finally {
            route.close()
            shadowOf(manager).lockCommunicationDevice(false)
        }
    }

    @Test
    @Config(sdk = [28])
    fun aFailedLegacyScoConnectionReleasesTheRequest() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        shadowOf(manager).setInputDevices(listOf(headset))
        manager.mode = AudioManager.MODE_NORMAL
        val route = BluetoothMicrophoneRoute(context)
        try {
            route.open()
            context.sendBroadcast(
                Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTING),
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(manager.isBluetoothScoOn)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        } finally {
            route.close()
        }
    }

    @Test fun connectedHeadsetIsSelectedAndRemovedHeadsetReleasesTheRoute() {
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            val context = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val shadow = shadowOf(manager)
            val headset = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
            shadow.setAvailableCommunicationDevices(listOf(headset))
            manager.mode = AudioManager.MODE_NORMAL
            val route = BluetoothMicrophoneRoute(context)
            try {
                route.open()
                assertSame(headset, manager.communicationDevice)
                assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
                shadow.removeAvailableCommunicationDevice(headset, true)
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(manager.communicationDevice)
                assertEquals(AudioManager.MODE_NORMAL, manager.mode)
                shadow.addAvailableCommunicationDevice(headset, true)
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(headset, manager.communicationDevice)
            } finally {
                route.close()
            }
            assertNull(manager.communicationDevice)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            // A queued device event after close must not reacquire the route.
            shadow.addAvailableCommunicationDevice(headset, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(manager.communicationDevice)
        }
    }

    @Test fun noHeadsetLeavesExistingModeAndRouteAlone() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.setCommunicationDevice(speaker)
        manager.mode = AudioManager.MODE_NORMAL
        val route = BluetoothMicrophoneRoute(context)
        route.open()
        route.close()
        assertSame(speaker, manager.communicationDevice)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test fun playbackOnlyBluetoothIsNeverChosenAsAMicrophone() {
        assertTrue(BluetoothMicrophoneRoute.isBluetoothMicrophone(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(BluetoothMicrophoneRoute.isBluetoothMicrophone(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertFalse(BluetoothMicrophoneRoute.isBluetoothMicrophone(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(BluetoothMicrophoneRoute.isBluetoothMicrophone(AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }

    @Test
    @Config(sdk = [28])
    fun legacyHeadsetUsesScoAndRemovalReleasesIt() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadow = shadowOf(manager)
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        shadow.setInputDevices(listOf(headset))
        manager.mode = AudioManager.MODE_NORMAL
        val route = BluetoothMicrophoneRoute(context)
        try {
            route.open()
            assertTrue(manager.isBluetoothScoOn)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            shadow.removeInputDevice(headset, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(manager.isBluetoothScoOn)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        } finally {
            route.close()
        }
    }
}
