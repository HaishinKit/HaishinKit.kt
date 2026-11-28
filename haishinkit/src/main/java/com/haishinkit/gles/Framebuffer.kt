package com.haishinkit.gles

import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.haishinkit.graphics.DynamicRangeMode

internal class Framebuffer(private val dynamicRangeMode: DynamicRangeMode = DynamicRangeMode.SDR) {
    val isEnabled: Boolean
        get() {
            return bounds.width() > 0 && bounds.height() > 0 && framebufferIds[0] != INVALID_VALUE
        }
    val textureId: Int
        get() = textureIds[0]

    var bounds = Rect()
        set(value) {
            if (field == value && isEnabled) return
            field = value
            release()
            if (value.width() <= 0 || value.height() <= 0) return
            try {
                // framebuffers.
                GLES20.glGenFramebuffers(1, framebufferIds, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferIds[0])
                Utils.checkGlError("glBindFramebuffer")

                // renderbuffers
                GLES20.glGenRenderbuffers(1, renderBufferIds, 0)
                GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBufferIds[0])
                GLES20.glRenderbufferStorage(
                    GLES20.GL_RENDERBUFFER,
                    GLES20.GL_DEPTH_COMPONENT16,
                    bounds.width(),
                    bounds.height(),
                )
                GLES20.glFramebufferRenderbuffer(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER,
                    renderBufferIds[0],
                )

                // textures.
                GLES20.glGenTextures(1, textureIds, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    if (dynamicRangeMode == DynamicRangeMode.HLG) GLES30.GL_RGBA16F else GLES20.GL_RGBA,
                    bounds.width(),
                    bounds.height(),
                    0,
                    GLES20.GL_RGBA,
                    if (dynamicRangeMode == DynamicRangeMode.HLG) GLES30.GL_HALF_FLOAT else GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR,
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR,
                )

                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    textureIds[0],
                    0,
                )

                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw RuntimeException("OpenGL framebuffer is incomplete: status=0x${status.toString(16)}")
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to allocate OpenGL framebuffer: width=${value.width()}, height=${value.height()}, dynamicRange=$dynamicRangeMode", e)
                release()
                // Allocation errors must not leak into the next valid resize.
                while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { }
            }
        }

    private var textureIds = intArrayOf(0)
    private var framebufferIds = intArrayOf(0)
    private var renderBufferIds = intArrayOf(0)

    fun render(lambda: () -> Unit) {
        if (!isEnabled) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferIds[0])
        Utils.checkGlError("glBindFramebuffer")
        lambda()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Utils.checkGlError("glBindFramebuffer")
    }

    fun release() {
        GLES20.glDeleteTextures(1, textureIds, 0)
        GLES20.glDeleteRenderbuffers(1, renderBufferIds, 0)
        GLES20.glDeleteFramebuffers(1, framebufferIds, 0)
        textureIds[0] = INVALID_VALUE
        renderBufferIds[0] = INVALID_VALUE
        framebufferIds[0] = INVALID_VALUE
    }

    companion object {
        private const val TAG = "Framebuffer"
        private const val INVALID_VALUE = 0
    }
}
