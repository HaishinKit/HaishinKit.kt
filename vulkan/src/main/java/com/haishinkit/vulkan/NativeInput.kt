package com.haishinkit.vulkan

import android.hardware.HardwareBuffer
import android.view.Surface

/** NDK ImageReader lets API 28 request GPU sampling usage without the API 29 Java overload. */
internal class NativeInput(width: Int, height: Int) : AutoCloseable {
    val surface: Surface

    @Suppress("unused")
    private var memory: Long = 0

    init {
        check(VkPixelTransform.isSupported())
        surface = nativeInitialize(width, height)
    }

    external fun acquire(): HardwareBuffer?

    external fun crop(): IntArray

    override fun close() {
        surface.release()
        nativeDispose()
    }

    private external fun nativeInitialize(
        width: Int,
        height: Int,
    ): Surface

    private external fun nativeDispose()
}
