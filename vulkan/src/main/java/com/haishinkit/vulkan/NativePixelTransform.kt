package com.haishinkit.vulkan

import android.content.Context
import android.content.res.AssetManager
import android.graphics.PixelFormat
import android.view.Surface
import com.haishinkit.graphics.VideoGravity

// All instance calls, including disposal, run on VkPixelTransform's worker.
internal class NativePixelTransform(context: Context) : AutoCloseable {
    @Suppress("unused")
    private var memory: Long = 0

    init {
        try {
            check(nativeIsSupported()) { "Required Vulkan features are unavailable" }
            nativeSetAssetManager(context.assets)
            nativeSetVideoGravity(VideoGravity.RESIZE.rawValue)
            nativeSetRotatesWithContent(false)
        } catch (error: Throwable) {
            nativeDispose()
            throw error
        }
    }

    fun setSurface(surface: Surface) = nativeSetSurface(surface)

    fun setImageExtent(
        width: Int,
        height: Int,
    ) = nativeSetImageExtent(width, height)

    fun setFrameRate(frameRate: Int) = nativeSetFrameRate(frameRate)

    fun createInputSurface(
        width: Int,
        height: Int,
    ): Surface =
        checkNotNull(nativeCreateInputSurface(width, height, PixelFormat.RGBA_8888))

    override fun close() = nativeDispose()

    private external fun nativeIsSupported(): Boolean

    private external fun nativeSetSurface(surface: Surface?)

    private external fun nativeSetImageExtent(
        width: Int,
        height: Int,
    )

    private external fun nativeSetAssetManager(assetManager: AssetManager)

    private external fun nativeCreateInputSurface(
        width: Int,
        height: Int,
        format: Int,
    ): Surface?

    private external fun nativeSetVideoGravity(value: Int)

    private external fun nativeSetRotatesWithContent(value: Boolean)

    private external fun nativeSetFrameRate(frameRate: Int)

    private external fun nativeDispose()

    companion object {
        private val loaded =
            try {
                System.loadLibrary("hkvulkan")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }

        fun isSupported(): Boolean = loaded && nativeIsDeviceSupported()

        private external fun nativeIsDeviceSupported(): Boolean
    }
}
