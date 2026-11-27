package com.haishinkit.gles.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.opengl.GLES20
import android.view.Choreographer
import androidx.core.graphics.createBitmap
import com.haishinkit.gles.Framebuffer
import com.haishinkit.gles.GraphicsContext
import com.haishinkit.gles.Utils
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.lang.Running
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.VideoScreenObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal class Screen(
    applicationContext: Context,
    dynamicRangeMode: DynamicRangeMode = DynamicRangeMode.SDR,
) : com.haishinkit.screen.Screen(applicationContext, dynamicRangeMode),
    Running,
    Choreographer.FrameCallback {
    val graphicsContext: GraphicsContext by lazy { GraphicsContext() }

    override var textureId: Int
        get() = framebuffer.textureId
        set(value) {
        }

    override var frame: Rect
        get() = super.frame
        set(value) {
            super.frame = value
            framebuffer.bounds = value
        }

    override val isRunning: AtomicBoolean = AtomicBoolean(false)

    private var textureIds = intArrayOf(0)
    private val renderer: Renderer by lazy { Renderer(applicationContext, dynamicRangeMode) { track -> videoTextureRegistry.get(track)?.source?.dynamicRangeMode } }
    private val framebuffer: Framebuffer by lazy { Framebuffer(dynamicRangeMode) }
    private var choreographer: Choreographer? = null
        set(value) {
            field?.removeFrameCallback(this)
            field = value
            field?.postFrameCallback(this)
        }
    private var videoTextureRegistry: VideoTextureRegistry = VideoTextureRegistry()

    override fun bind(screenObject: ScreenObject) {
        when (screenObject) {
            is VideoScreenObject -> {
                val track = screenObject.track
                val texture = videoTextureRegistry.get(track)
                updateCameraOrientation(screenObject, texture?.source)
                texture?.let {
                    screenObject.textureId = texture.id
                    screenObject.videoSize = texture.source.videoSize
                    screenObject.imageOrientation = texture.source.imageOrientation
                }
            }

            else -> {
                GLES20.glGenTextures(1, textureIds, 0)
                screenObject.textureId = textureIds[0]
            }
        }
    }

    override fun unbind(screenObject: ScreenObject) {
        clearCameraOrientation(screenObject)
        when (screenObject) {
            is VideoScreenObject -> {
                screenObject.textureId = 0
            }

            else -> {
                textureIds[0] = screenObject.textureId
                GLES20.glDeleteTextures(1, textureIds, 0)
                screenObject.textureId = 0
            }
        }
    }

    override fun attachVideo(
        track: Int,
        video: VideoSource?,
    ) {
        if (video == null) {
            videoTextureRegistry.unregister(track)
            findByClass(VideoScreenObject::class.java).filter { it.track == track }.forEach {
                it.textureId = 0
                updateCameraOrientation(it, null)
            }
        } else {
            val texture = videoTextureRegistry.register(track, video)
            findByClass(VideoScreenObject::class.java).filter { it.track == track }.forEach {
                it.textureId = texture.id
                it.videoSize = video.videoSize
                it.imageOrientation = video.imageOrientation
                updateCameraOrientation(it, video)
                it.invalidateLayout()
            }
        }
    }

    override fun readPixels(lambda: (bitmap: Bitmap?) -> Unit) {
        if (dynamicRangeMode == DynamicRangeMode.HLG) {
            lambda(null)
            return
        }
        val bitmap =
            createBitmap(frame.width(), frame.height())
        val byteBuffer =
            ByteBuffer.allocateDirect(frame.width() * frame.height() * 4).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
        framebuffer.render {
            graphicsContext.readPixels(frame.width(), frame.height(), byteBuffer)
        }
        bitmap.copyPixelsFromBuffer(byteBuffer)
        lambda(
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                frame.width(),
                frame.height(),
                null,
                false,
            ),
        )
    }

    override fun dispose() {
        stopRunning()
        super.dispose()
    }

    override fun startRunning() {
        if (isRunning.get()) return
        isRunning.set(true)
        graphicsContext.open(null, dynamicRangeMode)
        graphicsContext.makeCurrent(null)
        if (dynamicRangeMode == DynamicRangeMode.HLG) {
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(" ")
            require("GL_EXT_YUV_target" in extensions) { "HLG sources require GL_EXT_YUV_target" }
            require("GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions) { "HLG composition requires floating-point color buffers" }
            val probe = Framebuffer(DynamicRangeMode.HLG)
            try {
                probe.bounds = Rect(0, 0, 4, 4)
                check(probe.isEnabled) { "HLG composition requires a floating-point framebuffer" }
            } finally {
                probe.release()
            }
        }
        choreographer = Choreographer.getInstance()
    }

    override fun stopRunning() {
        if (!isRunning.get()) return
        isRunning.set(false)
        choreographer = null
        framebuffer.release()
        renderer.release()
        graphicsContext.close()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (isRunning.get()) {
            for (callback in callbacks) {
                callback.onEnterFrame()
            }
            choreographer?.postFrameCallback(this)
        }

        if (!framebuffer.isEnabled) return

        layout(renderer)
        framebuffer.render {
            val rgb = floatArrayOf(Color.red(backgroundColor) / 255f, Color.green(backgroundColor) / 255f, Color.blue(backgroundColor) / 255f)
            val working = if (dynamicRangeMode == DynamicRangeMode.HLG) com.haishinkit.gles.HlgColor.fromSrgb(rgb[0], rgb[1], rgb[2]) else rgb
            GLES20.glClearColor(working[0], working[1], working[2], 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            draw(renderer)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        GLES20.glFlush()
        Utils.checkGlError("glFlush")
    }

    companion object {
        @Suppress("unused")
        private val TAG = Screen::class.java.simpleName
    }
}
