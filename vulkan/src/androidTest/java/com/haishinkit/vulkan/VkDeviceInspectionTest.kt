package com.haishinkit.vulkan

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VkDeviceInspectionTest {
    @Test
    fun inspectionWorksWithoutRendererAndRetainsJsonSchema() {
        repeat(2) {
            val result = JSONObject(VkPixelTransform.inspectDevices())
            assertEquals(setOf("devices"), result.keys().asSequence().toSet())
            val devices = result.getJSONArray("devices")
            if (VkPixelTransform.isSupported()) assertTrue(devices.length() > 0)
            repeat(devices.length()) { index ->
                val device = devices.getJSONObject(index)
                assertEquals(
                    setOf("device_name", "device_type", "api_version", "driver_version", "vendor_id", "device_id", "extensions"),
                    device.keys().asSequence().toSet(),
                )
                assertTrue(device.getString("device_name").isNotEmpty())
                assertTrue(device.getString("device_type") in setOf("cpu", "integrated_gpu", "discrete_gpu", "virtual_gpu", "other"))
                listOf("api_version", "driver_version", "vendor_id", "device_id").forEach { key ->
                    assertTrue(device.getLong(key) in 0..0xffffffffL)
                }
                val extensions = device.getJSONArray("extensions")
                repeat(extensions.length()) { extension -> assertTrue(extensions.getString(extension).startsWith("VK_")) }
            }
        }
    }
}
