package com.haishinkit.vulkan

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Surface

/** Calls and buffer lifetimes are serialized by VkWorker. Rendering completes before returning. */
internal class NativeScene(context: Context) : AutoCloseable {
    @Suppress("unused")
    private var memory: Long = 0

    init {
        check(VkPixelTransform.isSupported()) { "Required Vulkan features are unavailable" }
        nativeInitialize(context.assets)
    }

    external fun setSurface(
        surface: Surface?,
        width: Int,
        height: Int,
    )

    external fun begin(
        width: Int,
        height: Int,
        backgroundColor: Int,
    )

    external fun layer(
        bitmap: Bitmap?,
        buffer: HardwareBuffer?,
        bounds: IntArray,
        matrix: FloatArray,
        shader: Array<String>,
        parameters: FloatArray,
    )

    external fun end(
        timestamp: Long,
        matrix: FloatArray,
        shader: Array<String>,
        parameters: FloatArray,
        backgroundColor: Int,
    )

    external fun abort()

    external fun readPixels(bitmap: Bitmap)

    override fun close() = nativeDispose()

    private external fun nativeInitialize(assets: AssetManager)

    private external fun nativeDispose()
}

internal fun VulkanShader.assets() = arrayOf(vertexAsset, fragmentAsset)
