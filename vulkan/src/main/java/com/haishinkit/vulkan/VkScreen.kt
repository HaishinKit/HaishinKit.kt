package com.haishinkit.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.util.Log
import android.util.Size
import android.view.Choreographer
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.MediaOutputScreenObject
import com.haishinkit.screen.Renderer
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.VideoScreenObject

/** Screen layout and input buffers shared by Vulkan outputs. This class never creates an EGL context. */
class VkScreen private constructor(context: Context) : Screen(context), Choreographer.FrameCallback {
    /** Receives rendering failures, including missing Vulkan shader implementations. */
    var onError: (Throwable) -> Unit = { Log.e("VkScreen", "Vulkan frame failed", it) }

    override var frame: Rect
        get() = VkWorker.run { Rect(super.frame) }
        set(value) =
            VkWorker.run {
                checkOpen()
                super.frame = Rect(value)
            }
    override var backgroundColor: Int
        get() = VkWorker.run { super.backgroundColor }
        set(value) =
            VkWorker.run {
                checkOpen()
                super.backgroundColor = value
            }

    private val outputs =
        linkedSetOf<VkPixelTransform>()
    private val tracks = mutableMapOf<Int, Input>()
    private val convertedBitmaps = mutableMapOf<ScreenObject, Pair<Int, Bitmap>>()
    private val nestedInputs = mutableMapOf<MediaOutputScreenObject, Input>()
    private var disposed = false
    private var choreographer: Choreographer? = null
    private var snapshotRenderer: NativeScene? = null
    private val renderer =
        object : Renderer {
            override fun layout(screenObject: ScreenObject) {}

            override fun draw(screenObject: ScreenObject) {}
        }

    init {
        VkWorker.run {
            choreographer = Choreographer.getInstance().also { it.postFrameCallback(this) }
        }
    }

    override fun addChild(child: ScreenObject?) =
        VkWorker.run {
            checkOpen()
            super.addChild(child)
        }

    override fun removeChild(child: ScreenObject?) = VkWorker.run { super.removeChild(child) }

    override fun bringChildToFront(child: ScreenObject) = VkWorker.run { super.bringChildToFront(child) }

    override fun sendChildToBack(child: ScreenObject) = VkWorker.run { super.sendChildToBack(child) }

    override fun transition(screenObjectContainer: ScreenObjectContainer) =
        VkWorker.run {
            checkOpen()
            super.transition(screenObjectContainer)
        }

    override fun getChildren(): List<ScreenObject> = VkWorker.run { super.getChildren().toList() }

    override fun <T : ScreenObject> findByClass(clazz: Class<T>): List<T> = VkWorker.run { super.findByClass(clazz) }

    override fun findById(id: String): ScreenObject? = VkWorker.run { super.findById(id) }

    override fun registerCallback(callback: Callback) =
        VkWorker.run {
            checkOpen()
            super.registerCallback(callback)
        }

    override fun unregisterCallback(callback: Callback) = VkWorker.run { super.unregisterCallback(callback) }

    override fun bind(screenObject: ScreenObject) {}

    override fun unbind(screenObject: ScreenObject) =
        VkWorker.run {
            convertedBitmaps.remove(screenObject)?.second?.recycle()
            if (screenObject is MediaOutputScreenObject) {
                screenObject.surface = null
                nestedInputs.remove(screenObject)?.close()
            }
        }

    override fun attachVideo(
        track: Int,
        video: VideoSource?,
    ) =
        VkWorker.run {
            checkOpen()
            tracks.remove(track)?.close()
            if (video != null) {
                require(video.videoSize.width > 0 && video.videoSize.height > 0)
                tracks[track] = createInput(video.videoSize, video) { video.surface = it }
            }
        }

    override fun readPixels(lambda: (Bitmap?) -> Unit) =
        VkWorker.run {
            checkOpen()
            prepare()
            if (frame.width() <= 0 || frame.height() <= 0) {
                lambda(null)
            } else {
                val native = snapshotRenderer ?: NativeScene(context).also { snapshotRenderer = it }
                compose(native)
                val shader = VulkanEffectRegistry.resolve(com.haishinkit.graphics.effect.DefaultVideoEffect.shared)
                native.end(System.nanoTime(), identity(), shader.assets(), shader.parameters, backgroundColor)
                val bitmap = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
                native.readPixels(bitmap)
                lambda(bitmap)
            }
        }

    override fun dispose() =
        VkWorker.run {
            if (!disposed) {
                disposed = true
                choreographer?.removeFrameCallback(this)
                outputs.toList().forEach { it.screen = null }
                tracks.values.forEach { it.close() }
                tracks.clear()
                nestedInputs.values.forEach { it.close() }
                nestedInputs.clear()
                convertedBitmaps.values.forEach { it.second.recycle() }
                convertedBitmaps.clear()
                snapshotRenderer?.close()
                snapshotRenderer = null
                super.dispose()
            }
        }

    override fun doFrame(frameTimeNanos: Long) {
        if (disposed) return
        choreographer?.postFrameCallback(this)
        try {
            callbacks.toList().forEach { it.onEnterFrame() }
            prepare()
        } catch (error: Exception) {
            reportError(error)
            return
        }
        outputs.toList().forEach { output ->
            try {
                output.render(frameTimeNanos)
            } catch (error: Exception) {
                // Failure of a preview/effect must not interrupt independent encoders.
                reportError(error)
            }
        }
    }

    internal fun register(output: VkPixelTransform) {
        checkOpen()
        outputs.add(output)
    }

    internal fun unregister(output: VkPixelTransform) {
        outputs.remove(output)
    }

    internal fun compose(native: NativeScene) {
        native.begin(frame.width(), frame.height(), backgroundColor)
        // Collect first: ScreenObject.draw catches RuntimeException, but shader/resource failures
        // must reach the caller and onError rather than silently omitting an object.
        val objects = mutableListOf<ScreenObject>()
        draw(
            object : Renderer {
                override fun layout(screenObject: ScreenObject) {}

                override fun draw(screenObject: ScreenObject) {
                    objects.add(screenObject)
                }
            },
        )
        try {
            objects.forEach { screenObject ->
                val bounds = screenObject.bounds
                if (bounds.isEmpty) return@forEach
                val original = (screenObject as? ImageScreenObject)?.bitmap
                val bitmap =
                    if (original != null && original.config != Bitmap.Config.ARGB_8888) {
                        val cached = convertedBitmaps[screenObject]
                        if (cached == null || cached.first != original.generationId) {
                            cached?.second?.recycle()
                            val converted = checkNotNull(original.copy(Bitmap.Config.ARGB_8888, false))
                            convertedBitmaps[screenObject] = original.generationId to converted
                            converted
                        } else {
                            cached.second
                        }
                    } else {
                        original
                    }
                val input =
                    when (screenObject) {
                        is MediaOutputScreenObject -> nestedInputs[screenObject]
                        is VideoScreenObject -> tracks[screenObject.track]
                        else -> null
                    }
                val buffer = input?.buffer
                if (bitmap == null && buffer == null) return@forEach
                val shader = VulkanEffectRegistry.resolve(screenObject.videoEffect)
                // GL's quad has vertex Y opposite to texture Y; scene.vert derives both from UV.
                // Change the input basis (matrix * flipY), not the output basis (flipY * matrix):
                // those orders differ for the camera's 90/270-degree sensor rotations.
                val matrix = screenObject.matrix.copyOf()
                for (row in 0..3) matrix[4 + row] = -matrix[4 + row]
                if (screenObject is VideoScreenObject && screenObject.imageOrientation in MIRRORED) {
                    for (row in 0..3) matrix[row] = -matrix[row]
                }
                native.layer(bitmap, buffer, intArrayOf(bounds.left, bounds.top, bounds.width(), bounds.height()) + (input?.crop() ?: IntArray(4)), matrix, shader.assets(), shader.parameters)
            }
        } catch (error: Throwable) {
            try {
                native.abort()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun prepare() {
        findByClass(VideoScreenObject::class.java).forEach { video ->
            val input = tracks[video.track]
            if (video !is MediaOutputScreenObject && input?.source != null) {
                video.videoSize = input.source.videoSize
                video.imageOrientation = input.source.imageOrientation
            }
        }
        layout(renderer)
        val mediaObjects = findByClass(MediaOutputScreenObject::class.java)
        nestedInputs.keys.filter { it !in mediaObjects }.forEach { removed ->
            removed.surface = null
            nestedInputs.remove(removed)?.close()
        }
        mediaObjects.forEach { video ->
            val size = Size(video.bounds.width().coerceAtLeast(1), video.bounds.height().coerceAtLeast(1))
            if (nestedInputs[video]?.size != size) {
                video.surface = null
                nestedInputs.remove(video)?.close()
                nestedInputs[video] = createInput(size) { video.surface = it }
            }
        }
        tracks.values.forEach { it.acquire() }
        nestedInputs.values.forEach { it.acquire() }
    }

    private fun createInput(
        size: Size,
        source: VideoSource? = null,
        attach: (Surface) -> Unit,
    ): Input {
        val input = Input(size, source)
        try {
            attach(input.reader.surface)
            return input
        } catch (error: Throwable) {
            try {
                input.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun reportError(error: Exception) {
        try {
            onError(error)
        } catch (callbackError: Exception) {
            Log.e("VkScreen", "Rendering error callback failed", callbackError)
        }
    }

    private fun checkOpen() {
        check(!disposed) { "VkScreen is disposed" }
    }

    private class Input(val size: Size, val source: VideoSource? = null) : AutoCloseable {
        val reader = NativeInput(size.width, size.height)
        var buffer: HardwareBuffer? = null

        fun crop(): IntArray {
            val rect = source?.videoCropRect ?: return reader.crop()
            val current = buffer ?: return IntArray(4)
            require(rect.left >= 0 && rect.top >= 0 && !rect.isEmpty && rect.right <= current.width && rect.bottom <= current.height) {
                "Video crop is outside its hardware buffer: $rect (${current.width}x${current.height})"
            }
            return intArrayOf(rect.left, rect.top, rect.right, rect.bottom)
        }

        fun acquire() {
            reader.acquire()?.let { next ->
                buffer?.close()
                buffer = next
            }
        }

        override fun close() {
            try {
                source?.surface = null
            } finally {
                try {
                    buffer?.close()
                } finally {
                    buffer = null
                    reader.close()
                }
            }
        }
    }

    companion object {
        private val MIRRORED = setOf(ImageOrientation.UP_MIRRORED, ImageOrientation.DOWN_MIRRORED, ImageOrientation.LEFT_MIRRORED, ImageOrientation.RIGHT_MIRRORED)

        /** Creates a Vulkan-only screen. Select alongside VkPixelTransform before creating streams. */
        fun create(context: Context): VkScreen = VkScreen(context).apply { frame = Rect(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT) }

        internal fun identity() =
            FloatArray(16).apply {
                this[0] = 1f
                this[5] = 1f
                this[10] = 1f
                this[15] = 1f
            }
    }
}
