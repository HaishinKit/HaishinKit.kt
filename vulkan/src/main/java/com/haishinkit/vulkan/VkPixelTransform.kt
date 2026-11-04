package com.haishinkit.vulkan

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.Screen
import java.util.concurrent.FutureTask

/**
 * Presents the current Screen composition using Vulkan.
 *
 * Screen composition and effects use the existing OpenGL renderer. Its output is
 * imported through an Android hardware buffer and presented by Vulkan.
 * Configure [PixelTransform.factory] before constructing streams and preview views.
 */
class VkPixelTransform(
    override val context: Context,
) : PixelTransform,
    AutoCloseable {
    override var screen: Screen? = null
        set(value) {
            if (field === value) return
            field = value
            update()
        }
    override var surface: Surface? = null
        set(value) {
            if (field === value) return
            field = value
            update()
        }
    override var imageExtent: Size = Size(0, 0)
        set(value) {
            require(value.width >= 0 && value.height >= 0)
            if (field == value) return
            field = value
            update()
        }
    override var videoGravity: VideoGravity = VideoGravity.RESIZE_ASPECT_FILL
        set(value) {
            field = value
            onWorker { producer?.videoGravity = value }
        }
    override var videoEffect: VideoEffect = DefaultVideoEffect.shared
        set(value) {
            field = value
            onWorker { producer?.videoEffect = value }
        }
    override var frameRate: Int = 60
        set(value) {
            require(value > 0)
            field = value
            onWorker {
                producer?.frameRate = value
                native?.setFrameRate(value)
            }
        }
    override var backgroundColor: Int = Color.BLACK
        set(value) {
            field = value
            onWorker { producer?.backgroundColor = value }
        }

    private val handler = Handler(thread.looper)
    private var producer: PixelTransform? = null
    private var native: NativePixelTransform? = null
    private var inputSurface: Surface? = null

    @Volatile
    private var closed = false

    /** Releases the bridge and native renderer. */
    override fun close() {
        if (closed) return
        onWorker {
            if (closed) return@onWorker
            release()
            closed = true
        }
    }

    private fun update() {
        onWorker {
            check(!closed) { "VkPixelTransform is closed" }
            release()
            val screen = screen ?: return@onWorker
            val surface = surface ?: return@onWorker
            if (imageExtent.width == 0 || imageExtent.height == 0) return@onWorker
            try {
                val renderer = NativePixelTransform(context)
                native = renderer
                renderer.setImageExtent(imageExtent.width, imageExtent.height)
                renderer.setFrameRate(frameRate)
                inputSurface = renderer.createInputSurface(imageExtent.width, imageExtent.height)
                renderer.setSurface(surface)
                producer =
                    PixelTransform.createOpenGL(context).apply {
                        imageExtent = this@VkPixelTransform.imageExtent
                        videoGravity = this@VkPixelTransform.videoGravity
                        videoEffect = this@VkPixelTransform.videoEffect
                        frameRate = this@VkPixelTransform.frameRate
                        backgroundColor = this@VkPixelTransform.backgroundColor
                        this.surface = inputSurface
                        this.screen = screen
                    }
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
    }

    private fun release() {
        val previousNative = native
        val previousProducer = producer
        val previousInput = inputSurface
        native = null
        producer = null
        inputSurface = null
        // Abandon the reader before joining a producer blocked in eglSwapBuffers.
        try {
            previousNative?.close()
        } finally {
            try {
                previousProducer?.close()
            } finally {
                previousInput?.release()
            }
        }
    }

    private fun onWorker(action: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            action()
        } else {
            val task = FutureTask(action, Unit)
            check(handler.post(task)) { "VkPixelTransform is closed" }
            try {
                task.get()
            } catch (error: java.util.concurrent.ExecutionException) {
                throw error.cause ?: error
            }
        }
    }

    companion object {
        // JNI state changes share a worker; idle views do not retain a thread each.
        private val thread by lazy { HandlerThread("VkPixelTransform").apply { start() } }

        /** Checks device extensions without changing the selected rendering backend. */
        fun isSupported(): Boolean = NativePixelTransform.isSupported()

        /** Uses OpenGL on devices without the required Vulkan capabilities. */
        fun create(context: Context): PixelTransform =
            if (isSupported()) VkPixelTransform(context) else PixelTransform.createOpenGL(context)
    }
}
