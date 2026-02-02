package com.haishinkit.gles.screen

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLUtils
import com.haishinkit.gles.ShaderLoader
import com.haishinkit.gles.Utils
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Renderer
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.VideoScreenObject
import javax.microedition.khronos.opengles.GL10

internal class Renderer(
    context: Context,
) : Renderer {
    private val shaderLoader by lazy {
        ShaderLoader(context)
    }

    override fun layout(screenObject: ScreenObject) {
        when (screenObject) {
            is VideoScreenObject -> {
                GLES20.glTexParameteri(
                    screenObject.target,
                    GL10.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST,
                )
                GLES20.glTexParameteri(
                    screenObject.target,
                    GL10.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR,
                )
                GLES20.glTexParameteri(
                    screenObject.target,
                    GL10.GL_TEXTURE_WRAP_S,
                    GL10.GL_CLAMP_TO_EDGE,
                )
                GLES20.glTexParameteri(
                    screenObject.target,
                    GL10.GL_TEXTURE_WRAP_T,
                    GL10.GL_CLAMP_TO_EDGE,
                )
            }

            is ImageScreenObject -> {
                val bitmap = screenObject.bitmap ?: return
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenObject.textureId)
                Utils.checkGlError("glBindTexture")
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                Utils.checkGlError("glPixelStorei")
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES20.glTexParameteri(
                    screenObject.target,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST,
                )
                GLES20.glTexParameteri(
                    screenObject.target,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR,
                )
                GLES20.glTexParameteri(
                    screenObject.target,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE,
                )
                GLES20.glTexParameteri(
                    screenObject.target,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE,
                )
            }
        }
    }

    override fun draw(screenObject: ScreenObject) {
        val program =
            shaderLoader.getProgram(screenObject.target, screenObject.videoEffect) ?: return
        GLES20.glViewport(
            screenObject.bounds.left,
            screenObject.bounds.top,
            screenObject.bounds.width(),
            screenObject.bounds.height(),
        )
        program.use()
        program.bind(screenObject.videoEffect)
        program.draw(screenObject)
    }

    fun release() {
        shaderLoader.release()
    }

    companion object {
        @Suppress("unused")
        private val TAG = Renderer::class.java.simpleName
    }
}
