package com.haishinkit.vulkan

import android.content.Context
import android.graphics.Color
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.Screen

/** Composes a VkScreen and resamples its Vulkan render target directly to the destination Surface. */
class VkPixelTransform(override val context: Context) : PixelTransform {
    override var screen: Screen? = null
        set(value) =
            VkWorker.run {
                check(!closed)
                require(value == null || value is VkScreen) { "VkPixelTransform requires VkScreen; configure Screen.factory before constructing streams" }
                (field as? VkScreen)?.unregister(this)
                field = value
                (value as? VkScreen)?.register(this)
            }
    override var surface: Surface? = null
        set(value) =
            VkWorker.run {
                check(!closed)
                field = value
                reset()
            }
    override var imageExtent = Size(0, 0)
        set(value) =
            VkWorker.run {
                require(value.width >= 0 && value.height >= 0)
                check(!closed)
                if (field != value) {
                    field = value
                    reset()
                }
            }
    override var videoGravity = VideoGravity.RESIZE_ASPECT_FILL
        set(value) =
            VkWorker.run {
                check(!closed)
                field = value
            }
    override var videoEffect: VideoEffect = BicubicVideoEffect()
        set(value) =
            VkWorker.run {
                check(!closed)
                VulkanEffectRegistry.resolve(value)
                field = value
            }
    override var frameRate = 60
        set(value) =
            VkWorker.run {
                check(!closed)
                require(value in 1..240)
                field = value
                pacer.reset()
            }
    override var backgroundColor = Color.BLACK
        set(value) =
            VkWorker.run {
                check(!closed)
                field = value
            }

    private var native: NativeScene? = null
    private var closed = false
    private val pacer = FramePacer()

    override fun close() =
        VkWorker.run {
            if (!closed) {
                screen = null
                surface = null
                closed = true
            }
        }

    internal fun render(timestamp: Long) {
        val screen = screen as? VkScreen ?: return
        if (surface == null || imageExtent.width == 0 || imageExtent.height == 0 || screen.frame.isEmpty) return
        if (!pacer.accept(timestamp, frameRate)) return
        val renderer =
            native ?: NativeScene(context).also {
                try {
                    it.setSurface(surface, imageExtent.width, imageExtent.height)
                    native = it
                } catch (error: Throwable) {
                    it.close()
                    throw error
                }
            }
        val matrix = VkScreen.identity()
        val sourceAspect = screen.frame.width().toFloat() / screen.frame.height()
        val targetAspect = imageExtent.width.toFloat() / imageExtent.height
        when (videoGravity) {
            VideoGravity.RESIZE -> Unit
            VideoGravity.RESIZE_ASPECT -> if (sourceAspect > targetAspect) matrix[5] = targetAspect / sourceAspect else matrix[0] = sourceAspect / targetAspect
            VideoGravity.RESIZE_ASPECT_FILL -> if (sourceAspect > targetAspect) matrix[0] = sourceAspect / targetAspect else matrix[5] = targetAspect / sourceAspect
        }
        val shader = VulkanEffectRegistry.resolve(videoEffect)
        screen.compose(renderer)
        renderer.end(timestamp, matrix, shader.assets(), shader.parameters, backgroundColor)
    }

    private fun reset() {
        native?.close()
        native = null
        pacer.reset()
    }

    companion object {
        fun isSupported(): Boolean = NativePixelTransform.isSupported()

        /** Select this together with VkScreen; unsupported devices must select both OpenGL factories. */
        fun create(context: Context): PixelTransform = VkPixelTransform(context)
    }
}
