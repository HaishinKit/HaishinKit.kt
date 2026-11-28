package com.haishinkit.gles

import android.opengl.GLES20
import android.util.Log
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.ScreenObject
import java.lang.reflect.Method
import java.nio.FloatBuffer
import java.nio.IntBuffer

internal class Program(
    private val id: Int = INVALID_VALUE,
    private val vertexShader: Int = INVALID_VALUE,
    private val fragmentShaper: Int = INVALID_VALUE,
    private val positionHandle: Int = INVALID_VALUE,
    private val texCoordHandle: Int = INVALID_VALUE,
    private val textureHandle: Int = INVALID_VALUE,
    private val mvpMatrixHandle: Int = INVALID_VALUE,
    private val handlers: Map<Int, Method>,
) {
    private var texCoordBuffer = Utils.createFloatBuffer(TEX_COORDS_ROTATION_0)
    private val vertexBuffer = Utils.createFloatBuffer(VERTECES)

    fun use() {
        GLES20.glUseProgram(id)
        Utils.checkGlError("glUseProgram")
    }

    fun bind(visualEffect: VideoEffect) {
        for (handler in handlers) {
            when (val value = handler.value.invoke(visualEffect)) {
                is Int -> GLES20.glUniform1i(handler.key, value)
                is IntBuffer -> {
                    when (value.remaining()) {
                        1 -> GLES20.glUniform1iv(handler.key, 1, value)
                        2 -> GLES20.glUniform2iv(handler.key, 1, value)
                        3 -> GLES20.glUniform3iv(handler.key, 1, value)
                        4 -> GLES20.glUniform4iv(handler.key, 1, value)
                    }
                }

                is Float -> GLES20.glUniform1f(handler.key, value)
                is FloatBuffer -> {
                    when (value.remaining()) {
                        1 -> GLES20.glUniform1fv(handler.key, 1, value)
                        2 -> GLES20.glUniform2fv(handler.key, 1, value)
                        3 -> GLES20.glUniform3fv(handler.key, 1, value)
                        4 -> GLES20.glUniform4fv(handler.key, 1, value)
                    }
                }

                else -> {
                    Log.e(TAG, "Skipping unsupported shader uniform: method=${handler.value.name}, type=${value?.javaClass?.name ?: "null"}, location=${handler.key}")
                }
            }
        }
    }

    fun draw(
        screenObject: ScreenObject,
        cornerClip: IntArray? = null,
        textureTransform: FloatArray? = null,
    ) {
        for (vertex in 0 until 4) {
            val u = TEX_COORDS_ROTATION_0[vertex * 2]
            val v = TEX_COORDS_ROTATION_0[vertex * 2 + 1]
            if (textureTransform == null) {
                texCoordBuffer.put(vertex * 2, u)
                texCoordBuffer.put(vertex * 2 + 1, v)
            } else {
                // External video geometry already flips vertically. Cancel that flip in
                // the input coordinates before applying the producer's crop/transform.
                texCoordBuffer.put(vertex * 2, textureTransform[0] * u + textureTransform[4] * (1f - v) + textureTransform[12])
                texCoordBuffer.put(vertex * 2 + 1, textureTransform[1] * u + textureTransform[5] * (1f - v) + textureTransform[13])
            }
        }
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        Utils.checkGlError("glEnableVertexAttribArray")
        GLES20.glVertexAttribPointer(
            texCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            texCoordBuffer,
        )
        Utils.checkGlError("glVertexAttribPointer")

        GLES20.glEnableVertexAttribArray(positionHandle)
        Utils.checkGlError("glEnableVertexAttribArray")
        GLES20.glVertexAttribPointer(
            positionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer,
        )
        Utils.checkGlError("glVertexAttribPointer")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        Utils.checkGlError("glActiveTexture")
        GLES20.glUniform1i(textureHandle, 0)
        Utils.checkGlError("glUniform1i")

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, screenObject.matrix, 0)
        Utils.checkGlError("glUniformMatrix4fv")

        GLES20.glBindTexture(screenObject.target, screenObject.textureId)
        Utils.checkGlError("glBindTexture")

        drawQuad(screenObject, cornerClip)
        Utils.checkGlError("glDrawArrays")

        GLES20.glDisableVertexAttribArray(texCoordHandle)
        Utils.checkGlError("glDisableVertexAttribArray")
        GLES20.glDisableVertexAttribArray(positionHandle)
        Utils.checkGlError("glDisableVertexAttribArray")

        GLES20.glUseProgram(0)
        Utils.checkGlError("glUseProgram")
        GLES20.glBindTexture(screenObject.target, 0)
        Utils.checkGlError("glBindTexture")
    }

    fun dispose() {
        GLES20.glDetachShader(id, vertexShader)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDetachShader(id, fragmentShaper)
        GLES20.glDeleteShader(fragmentShaper)
        GLES20.glDeleteProgram(id)
    }

    private fun drawQuad(
        screenObject: ScreenObject,
        clip: IntArray?,
    ) {
        if (clip == null) {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            return
        }
        val bounds = screenObject.bounds
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        try {
            for (i in clip.indices step 4) {
                GLES20.glScissor(bounds.left + clip[i], bounds.top + clip[i + 1], clip[i + 2] - clip[i], clip[i + 3] - clip[i + 1])
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
    }

    companion object {
        private const val INVALID_VALUE = 0
        private val TAG = Program::class.java.simpleName

        private val VERTECES =
            floatArrayOf(
                -1.0f,
                1.0f,
                // top-left
                0.0f,
                -1.0f,
                -1.0f,
                // bottom-left
                0.0f,
                1.0f,
                1.0f,
                // bottom-right
                0.0f,
                1.0f,
                -1.0f,
                // top-right
                0.0f,
            )

        private val TEX_COORDS_ROTATION_0 =
            floatArrayOf(
                0.0f,
                0.0f,
                0.0f,
                1.0f,
                1.0f,
                0.0f,
                1.0f,
                1.0f,
            )
    }
}
