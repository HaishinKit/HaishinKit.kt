package com.haishinkit.gles.screen

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
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
    private var textureIds = intArrayOf(0)
    private var surfaceTextures = mutableMapOf<Int, SurfaceTexture>()
    private val shaderLoader by lazy {
        ShaderLoader(context)
    }

    override fun layout(screenObject: ScreenObject) {
        when (screenObject) {
            is VideoScreenObject -> {
                surfaceTextures[screenObject.id]?.setDefaultBufferSize(
                    screenObject.videoSize.width,
                    screenObject.videoSize.height,
                )
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
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenObject.id)
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

    override fun bind(screenObject: ScreenObject) {
        GLES20.glGenTextures(1, textureIds, 0)
        screenObject.id = textureIds[0]
        when (screenObject) {
            is VideoScreenObject -> {
                SurfaceTexture(screenObject.id).apply {
                    surfaceTextures[screenObject.id] = this
                    setDefaultBufferSize(
                        screenObject.videoSize.width,
                        screenObject.videoSize.height,
                    )
                    setOnFrameAvailableListener(screenObject)
                    screenObject.surface = Surface(this)
                }
            }
        }
    }

    override fun unbind(screenObject: ScreenObject) {
        textureIds[0] = screenObject.id
        when (screenObject) {
            is VideoScreenObject -> {
                surfaceTextures.remove(screenObject.id)?.let {
                    screenObject.surface = null
                    it.setOnFrameAvailableListener(null)
                    it.release()
                }
            }
        }
        GLES20.glDeleteTextures(1, textureIds, 0)
        screenObject.id = 0
    }

    fun release() {
        shaderLoader.release()
    }

    companion object {
        @Suppress("unused")
        private val TAG = Renderer::class.java.simpleName
    }
}
