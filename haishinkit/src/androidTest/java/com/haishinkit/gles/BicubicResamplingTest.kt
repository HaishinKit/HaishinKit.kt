package com.haishinkit.gles

import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.VideoScreenObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class BicubicResamplingTest {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE

    @Before
    fun openContext() {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configurations = arrayOfNulls<EGLConfig>(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x40,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
                ),
                0,
                configurations,
                0,
                1,
                IntArray(1),
                0,
            ),
        )
        val configuration = checkNotNull(configurations[0])
        context =
            EGL14.eglCreateContext(
                display, configuration, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
        surface =
            EGL14.eglCreatePbufferSurface(
                display, configuration,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
    }

    @After
    fun closeContext() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    @Test
    fun fractionalMinificationMatchesFullSixteenTexelBSpline() = verify(16, 16, 11, 11)

    @Test
    fun wideTextureRetainsSubpixelAccuracy() = verify(2048, 8, 854, 5)

    @Test
    fun videoLayoutConfiguresItsOwnTextureForLinearMinification() {
        val source = Framebuffer().apply { bounds = Rect(0, 0, 16, 16) }
        val other = Framebuffer().apply { bounds = Rect(0, 0, 16, 16) }
        val renderer = com.haishinkit.gles.screen.Renderer(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            for (texture in intArrayOf(source.textureId, other.textureId)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            }
            renderer.layout(VideoScreenObject(target = GLES20.GL_TEXTURE_2D).apply { textureId = source.textureId })
            val filter = IntArray(1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.textureId)
            GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter, 0)
            assertEquals(GLES20.GL_LINEAR, filter[0])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, other.textureId)
            GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter, 0)
            assertEquals(GLES20.GL_NEAREST, filter[0])
        } finally {
            renderer.release()
            source.release()
            other.release()
        }
    }

    private fun verify(
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        val source = Framebuffer().apply { bounds = Rect(0, 0, width, height) }
        val destination = Framebuffer().apply { bounds = Rect(0, 0, outputWidth, outputHeight) }
        val loader = ShaderLoader(InstrumentationRegistry.getInstrumentation().targetContext)
        val values = IntArray(width * height) { index -> ((index % width) * 37 + (index / width) * 71) % 256 }
        val pixels = ByteBuffer.allocateDirect(values.size * 4)
        values.forEach { value -> pixels.put(value.toByte()).put(value.toByte()).put(value.toByte()).put(255.toByte()) }
        pixels.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.textureId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        try {
            val effect = BicubicVideoEffect()
            val program = loader.getProgram(GLES20.GL_TEXTURE_2D, effect)
            assertNotNull("Production bicubic shader must compile and link", program)
            val image = ImageScreenObject().apply { textureId = source.textureId }
            val output = ByteBuffer.allocateDirect(outputWidth * outputHeight * 4)
            destination.render {
                GLES20.glViewport(0, 0, outputWidth, outputHeight)
                program!!.use()
                program.bind(effect)
                program.draw(image)
                GLES20.glReadPixels(0, 0, outputWidth, outputHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, output)
            }
            var error = 0.0
            for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
                val sx = (x + 0.5) * width / outputWidth - 0.5
                val sy = (y + 0.5) * height / outputHeight - 0.5
                var expected = 0.0
                for (iy in floor(sy).toInt() - 1..floor(sy).toInt() + 2) {
                    for (ix in floor(sx).toInt() - 1..floor(sx).toInt() + 2) {
                        expected += values[iy.coerceIn(0, height - 1) * width + ix.coerceIn(0, width - 1)] * weight(sx - ix) * weight(sy - iy)
                    }
                }
                val actual = output.get((y * outputWidth + x) * 4).toInt() and 255
                error = max(error, abs(actual - expected))
            }
            assertTrue("GPU bicubic differs from the clamped 16-texel reference by $error / 255", error <= 3.0)
        } finally {
            loader.release()
            source.release()
            destination.release()
        }
    }

    private fun weight(distance: Double): Double {
        val x = abs(distance)
        return when {
            x < 1 -> (4 - 6 * x * x + 3 * x * x * x) / 6
            x < 2 -> (2 - x) * (2 - x) * (2 - x) / 6
            else -> 0.0
        }
    }
}
