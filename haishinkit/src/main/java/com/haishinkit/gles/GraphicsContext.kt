package com.haishinkit.gles

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

internal class GraphicsContext {
    var version: Int = 0
        private set
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun open(shareGraphicsContext: GraphicsContext?) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

        if (display === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        config = chooseConfig(3)
        if (config != null) {
            val context =
                EGL14.eglCreateContext(
                    display,
                    config,
                    shareGraphicsContext?.context ?: EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                    0,
                )
            if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                this.context = context
                this.version = 3
            }
        }

        // fall back to 2.0
        if (context == EGL14.EGL_NO_CONTEXT) {
            config = chooseConfig(2)
            if (config == null) {
                throw RuntimeException("unable to find a suitable EGLConfig")
            }
            context =
                EGL14.eglCreateContext(
                    display,
                    config,
                    shareGraphicsContext?.context ?: EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
            this.version = 2
        }
    }

    fun makeCurrent(surface: EGLSurface?) {
        if (surface == null) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)
            Utils.checkGlError("eglMakeCurrent")
            this.surface = EGL14.EGL_NO_SURFACE
        } else {
            if (this.surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, this.surface)
            }
            EGL14.eglMakeCurrent(display, surface, surface, context)
            Utils.checkGlError("eglMakeCurrent")
            this.surface = surface
        }
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(timestamp: Long): Boolean = EGLExt.eglPresentationTimeANDROID(display, surface, timestamp)

    fun createWindowSurface(surface: Surface?): EGLSurface? {
        if (surface == null) {
            return null
        }
        return EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            SURFACE_ATTRIBUTES,
            0,
        )
    }

    fun readPixels(
        width: Int,
        height: Int,
        buffer: ByteBuffer,
    ) {
        buffer.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
    }

    fun close() {
        release()
    }

    protected fun finalize() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                release()
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "", e)
        }
    }

    private fun release() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }

    private fun chooseConfig(version: Int): EGLConfig? {
        var renderableType = EGL14.EGL_OPENGL_ES2_BIT

        if (2 < version) {
            renderableType = renderableType or EGLExt.EGL_OPENGL_ES3_BIT_KHR
        }

        val attributes = CONFIG_ATTRIBUTES_WITH_CONTEXT
        attributes[9] = renderableType

        val configs: Array<EGLConfig?> = arrayOfNulls(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                display,
                attributes,
                0,
                configs,
                0,
                configs.size,
                numConfigs,
                0,
            )
        ) {
            return null
        }

        return configs[0]
    }

    companion object {
        private val TAG = GraphicsContext::class.java.toString()
        private const val EGL_RECORDABLE_ANDROID: Int = 0x3142
        private val SURFACE_ATTRIBUTES = intArrayOf(EGL14.EGL_NONE)
        private val CONFIG_ATTRIBUTES_WITH_CONTEXT =
            intArrayOf(
                // R
                EGL14.EGL_RED_SIZE,
                8,
                // G
                EGL14.EGL_GREEN_SIZE,
                8,
                // B
                EGL14.EGL_BLUE_SIZE,
                8,
                // A
                EGL14.EGL_ALPHA_SIZE,
                8,
                // TYPE
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                // RECORDABLE
                EGL_RECORDABLE_ANDROID,
                1,
                // EOA
                EGL14.EGL_NONE,
            )
    }
}
