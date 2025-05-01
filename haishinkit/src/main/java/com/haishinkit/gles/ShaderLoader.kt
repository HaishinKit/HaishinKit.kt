package com.haishinkit.gles

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.graphics.glsl.RequirementsDirective
import com.haishinkit.graphics.glsl.Uniform
import java.io.FileNotFoundException
import java.lang.reflect.Method
import java.util.Locale

internal class ShaderLoader(
    private val applicationContext: Context,
) {
    private var texture2dPrograms = mutableMapOf<VideoEffect, Program>()
    private var textureOesPrograms = mutableMapOf<VideoEffect, Program>()

    fun getProgram(
        target: Int,
        videoEffect: VideoEffect,
    ): Program? =
        if (target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
            if (!textureOesPrograms.containsKey(videoEffect)) {
                createProgram(target, videoEffect)?.let {
                    textureOesPrograms[videoEffect] = it
                }
            }
            textureOesPrograms[videoEffect]
        } else {
            if (!texture2dPrograms.containsKey(videoEffect)) {
                createProgram(target, videoEffect)?.let {
                    texture2dPrograms[videoEffect] = it
                }
            }
            texture2dPrograms[videoEffect]
        }

    fun release() {
        texture2dPrograms.forEach {
            it.value.dispose()
        }
        texture2dPrograms.clear()
        textureOesPrograms.forEach {
            it.value.dispose()
        }
        textureOesPrograms.clear()
    }

    private fun createProgram(
        target: Int,
        videoEffect: VideoEffect,
    ): Program? {
        val vertexShader = loadShader(target, GLES20.GL_VERTEX_SHADER, videoEffect)
        if (vertexShader == 0) {
            return null
        }
        val fragmentShader = loadShader(target, GLES20.GL_FRAGMENT_SHADER, videoEffect)
        if (fragmentShader == 0) {
            return null
        }
        var program = GLES20.glCreateProgram()
        Utils.checkGlError(GL_CREATE_PROGRAM)
        if (program == 0) {
            Log.e(TAG, "Could not create program")
        }
        GLES20.glAttachShader(program, vertexShader)
        Utils.checkGlError(GL_ATTACH_SHADER)
        GLES20.glAttachShader(program, fragmentShader)
        Utils.checkGlError(GL_ATTACH_SHADER)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return Program(
            program,
            vertexShader,
            fragmentShader,
            GLES20.glGetAttribLocation(program, "aPosition"),
            GLES20.glGetAttribLocation(program, "aTexcoord"),
            GLES20.glGetAttribLocation(program, "uTexture"),
            GLES20.glGetUniformLocation(program, "uMVPMatrix"),
            handlers(program, videoEffect),
        )
    }

    private fun handlers(
        program: Int,
        videoEffect: VideoEffect,
    ): Map<Int, Method> {
        val handlers = mutableMapOf<Int, Method>()
        val clazz = videoEffect::class.java
        for (method in clazz.methods) {
            method.getAnnotation(Uniform::class.java) ?: continue
            val propertyName =
                method.name
                    .split("$")[0]
                    .substring(3, 4)
                    .lowercase(Locale.ROOT) + method.name.split("$")[0].substring(4)
            val location = GLES20.glGetUniformLocation(program, propertyName)
            handlers[location] = clazz.getDeclaredMethod(method.name.split("$")[0])
        }
        return handlers
    }

    private fun loadShader(
        target: Int,
        shaderType: Int,
        videoEffect: VideoEffect,
    ): Int {
        var suffix = ""
        var shader = GLES20.glCreateShader(shaderType)
        Utils.checkGlError("glCreateShader type=$shaderType")
        if (shaderType == GLES20.GL_VERTEX_SHADER &&
            videoEffect::class.java.getAnnotation(
                RequirementsDirective::class.java,
            ) != null
        ) {
            suffix = "-300"
        }
        GLES20.glShaderSource(shader, readFile(target, shaderType, videoEffect.name, suffix))
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $shaderType:")
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun readFile(
        target: Int,
        shaderType: Int,
        source: String,
        suffix: String = "",
    ): String {
        var fileName = "shaders/$source$suffix"
        fileName +=
            if (shaderType == GLES20.GL_VERTEX_SHADER) {
                ".vert"
            } else {
                ".frag"
            }
        try {
            val inputStream = applicationContext.assets?.open(fileName) ?: return ""
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            if (target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES && shaderType == GLES20.GL_FRAGMENT_SHADER) {
                return EXTENSION_OES +
                    String(buffer).replace(
                        "uniform sampler2D uTexture",
                        "uniform samplerExternalOES uTexture",
                    )
            }
            return String(buffer)
        } catch (e: FileNotFoundException) {
            return readFile(target, shaderType, "default", suffix)
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
            return ""
        }
    }

    companion object {
        private val TAG = ShaderLoader::class.java.simpleName

        private const val EXTENSION_OES = "#extension GL_OES_EGL_image_external : require\n\n"
        private const val GL_CREATE_PROGRAM = "glCreateProgram"
        private const val GL_ATTACH_SHADER = "glAttachShader"
    }
}
