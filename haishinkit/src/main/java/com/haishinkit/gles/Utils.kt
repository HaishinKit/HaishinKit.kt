package com.haishinkit.gles

import android.opengl.GLES20
import com.haishinkit.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object Utils {
    @Suppress("unused")
    private val TAG = Utils::class.java.toString()

    fun checkGlError(op: String) {
        if (BuildConfig.DEBUG) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw RuntimeException(op + ": glError 0x" + Integer.toHexString(error))
            }
        }
    }

    fun createFloatBuffer(array: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(array.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        return buffer.asFloatBuffer().apply {
            put(array)
            position(0)
        }
    }
}
