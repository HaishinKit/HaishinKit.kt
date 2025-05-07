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
import com.haishinkit.screen.ScreenObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal class Screen(
    applicationContext: Context,
) : com.haishinkit.screen.Screen(applicationContext),
    Running,
    Choreographer.FrameCallback {
    val graphicsContext: GraphicsContext by lazy { GraphicsContext() }
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

    override fun bind(screenObject: ScreenObject) {
        renderer.bind(screenObject)
    }

    override fun unbind(screenObject: ScreenObject) {
        renderer.unbind(screenObject)
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
        private val TAG = Screen::class.java.simpleName
    }
}
