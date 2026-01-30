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
import com.haishinkit.lang.Running
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.VideoScreenObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal class Screen(
    applicationContext: Context,
) : com.haishinkit.screen.Screen(applicationContext),
    Running,
    Choreographer.FrameCallback {
    val graphicsContext: GraphicsContext by lazy { GraphicsContext() }

    private var textureIds = intArrayOf(0)

    override var id: Int
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

    private val renderer: Renderer by lazy { Renderer(applicationContext) }
    private val framebuffer: Framebuffer by lazy { Framebuffer() }
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
                videoTextureRegistry.getTextureIdByTrack(track)?.let { id ->
                    screenObject.id = id
                }
            }
            else -> {
                GLES20.glGenTextures(1, textureIds, 0)
                screenObject.id = textureIds[0]
            }
        }
    }

    override fun unbind(screenObject: ScreenObject) {
        when (screenObject) {
            is VideoScreenObject -> {
            }
            else -> {
                textureIds[0] = screenObject.id
                GLES20.glDeleteTextures(1, textureIds, 0)
                screenObject.id = 0
            }
        }
    }

    override fun attachVideo(
        track: Int,
        video: VideoSource?,
    ) {
        if (video == null) {
            videoTextureRegistry.unregister(track)
        } else {
            videoTextureRegistry.register(track, video)
            videoTextureRegistry.getTextureIdByTrack(track)?.let { id ->
                getScreenObjects(VideoScreenObject::class.java).forEach {
                    if (it.track == track) {
                        it.id = id
                        it.videoSize = video.videoSize
                        it.imageOrientation = video.imageOrientation
                    }
                }
            }
        }
    }

    override fun readPixels(lambda: (bitmap: Bitmap?) -> Unit) {
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
        graphicsContext.open(null)
        graphicsContext.makeCurrent(null)
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
            GLES20.glClearColor(
                (Color.red(backgroundColor) / 255).toFloat(),
                (Color.green(backgroundColor) / 255).toFloat(),
                (Color.blue(backgroundColor) / 255).toFloat(),
                0f,
            )
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
