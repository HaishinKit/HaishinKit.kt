package com.haishinkit.gles

import android.graphics.Rect
import android.opengl.GLES20
import android.util.Log

internal class Framebuffer {
    val isEnabled: Boolean
        get() {
            return bounds.width() != 0 && bounds.height() != 0
        }
    val textureId: Int
        get() = textureIds[0]

    var bounds = Rect()
        set(value) {
            if (field == value) return
            field = value
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
                    GLES20.GL_RGBA,
                    bounds.width(),
                    bounds.height(),
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
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
                    throw RuntimeException("" + status)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "", e)
                release()
            }
        }

    private var textureIds = intArrayOf(1)
    private var framebufferIds = intArrayOf(1)
    private var renderBufferIds = intArrayOf(1)

    fun render(lambda: () -> Unit) {
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
