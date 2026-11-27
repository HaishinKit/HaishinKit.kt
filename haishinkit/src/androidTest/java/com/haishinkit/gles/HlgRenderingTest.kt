package com.haishinkit.gles

import android.graphics.Rect
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.GLES20
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.DynamicRangeMode
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.DefaultVideoEffect
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class HlgRenderingTest {
    @org.junit.Before fun requireHlgGraphics() {
        val graphics = GraphicsContext()
        try {
            try {
                graphics.open(null, DynamicRangeMode.HLG)
                graphics.makeCurrent(null)
            } catch (unsupported: IllegalArgumentException) {
                org.junit.Assume.assumeNoException(unsupported)
            } catch (unsupported: IllegalStateException) {
                org.junit.Assume.assumeNoException(unsupported)
            }
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(" ")
            org.junit.Assume.assumeTrue("Requires YUV sampling and floating-point composition", "GL_EXT_YUV_target" in extensions && ("GL_EXT_color_buffer_float" in extensions || "GL_EXT_color_buffer_half_float" in extensions))
        } finally {
            graphics.close()
        }
    }

    @Test fun sdrReferenceWhiteIs203NitsInTheLinearFramebuffer() {
        val graphics = GraphicsContext()
        graphics.open(null, DynamicRangeMode.HLG)
        graphics.makeCurrent(null)
        val framebuffer = Framebuffer(DynamicRangeMode.HLG).apply { bounds = Rect(0, 0, 16, 16) }
        val renderer = com.haishinkit.gles.screen.Renderer(InstrumentationRegistry.getInstrumentation().targetContext, DynamicRangeMode.HLG)
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        val image =
            com.haishinkit.screen.ImageScreenObject().apply {
                textureId = texture[0]
                frame = Rect(0, 0, 16, 16)
                bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            }
        try {
            image.layout(renderer)
            framebuffer.render {
                renderer.draw(image)
                val pixel = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                GLES20.glReadPixels(8, 8, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, pixel)
                for (channel in 0..2) org.junit.Assert.assertEquals(0.203f, pixel[channel], 0.001f)
                org.junit.Assert.assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            }
        } finally {
            image.bitmap?.recycle()
            GLES20.glDeleteTextures(1, texture, 0)
            renderer.release()
            framebuffer.release()
            graphics.close()
        }
    }

    @Test fun halfTransparentBitmapUsesStraightLinearColorBeforeBlending() {
        val graphics = GraphicsContext()
        graphics.open(null, DynamicRangeMode.HLG)
        graphics.makeCurrent(null)
        val framebuffer = Framebuffer(DynamicRangeMode.HLG).apply { bounds = Rect(0, 0, 16, 16) }
        val renderer = com.haishinkit.gles.screen.Renderer(InstrumentationRegistry.getInstrumentation().targetContext, DynamicRangeMode.HLG)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val image =
            com.haishinkit.screen.ImageScreenObject().apply {
                textureId = ids[0]
                frame = Rect(0, 0, 16, 16)
                bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0x80ffffff.toInt()) }
            }
        try {
            image.layout(renderer)
            framebuffer.render {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                renderer.draw(image)
                GLES20.glDisable(GLES20.GL_BLEND)
                val values = readFloatPixel()
                for (i in 0..2) org.junit.Assert.assertEquals(0.203f * 128f / 255f, values[i], 0.002f)
            }
        } finally {
            image.bitmap?.recycle()
            GLES20.glDeleteTextures(1, ids, 0)
            renderer.release()
            framebuffer.release()
            graphics.close()
        }
    }

    @Test fun effectSwitchRetainsHlgOutputAndMakesItOpaque() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graphics = GraphicsContext()
        graphics.open(null, DynamicRangeMode.HLG)
        graphics.makeCurrent(null)
        val source = Framebuffer(DynamicRangeMode.HLG).apply { bounds = Rect(0, 0, 16, 16) }
        val destination = Framebuffer(DynamicRangeMode.HLG).apply { bounds = Rect(0, 0, 16, 16) }
        val fake =
            object : com.haishinkit.screen.Screen(context, DynamicRangeMode.HLG) {
                override fun readPixels(lambda: (android.graphics.Bitmap?) -> Unit) {}

                override fun bind(screenObject: com.haishinkit.screen.ScreenObject) {}

                override fun unbind(screenObject: com.haishinkit.screen.ScreenObject) {}

                override fun attachVideo(
                    track: Int,
                    video: com.haishinkit.media.source.VideoSource?,
                ) {}
            }
        val transform = PixelTransform(context)
        val quad =
            com.haishinkit.screen.VideoScreenObject(target = GLES20.GL_TEXTURE_2D).apply {
                frame = Rect(0, 0, 16, 16)
                videoSize = android.util.Size(16, 16)
                textureId = source.textureId
            }
        val renderer = com.haishinkit.gles.screen.Renderer(context)
        try {
            source.render {
                GLES20.glClearColor(0.203f, 0.203f, 0.203f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            transform.screen = fake
            transform.videoEffect = DefaultVideoEffect.shared
            val program = PixelTransform::class.java.getDeclaredField("program").apply { isAccessible = true }.get(transform) as Program
            transform.videoEffect = BicubicVideoEffect()
            transform.videoEffect = DefaultVideoEffect.shared
            org.junit.Assert.assertSame(program, PixelTransform::class.java.getDeclaredField("program").apply { isAccessible = true }.get(transform))
            quad.layout(renderer)
            destination.render {
                GLES20.glViewport(0, 0, 16, 16)
                program.use()
                program.bind(DefaultVideoEffect.shared)
                program.draw(quad)
                val values = readFloatPixel()
                for (i in 0..2) org.junit.Assert.assertEquals(0.75f, values[i], 0.003f)
                org.junit.Assert.assertEquals(1f, values[3], 0f)
            }
        } finally {
            transform.screen = null
            renderer.release()
            source.release()
            destination.release()
            graphics.close()
        }
    }

    @Test fun unsupportedHlgSourceVideoEffectFallsBackWithoutKillingTheRenderer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graphics = GraphicsContext()
        graphics.open(null, DynamicRangeMode.HLG)
        graphics.makeCurrent(null)
        val source = Framebuffer().apply { bounds = Rect(0, 0, 16, 16) }
        val destination = Framebuffer(DynamicRangeMode.HLG).apply { bounds = Rect(0, 0, 16, 16) }
        val quad =
            com.haishinkit.screen.VideoScreenObject(target = GLES20.GL_TEXTURE_2D).apply {
                frame = Rect(0, 0, 16, 16)
                videoSize = android.util.Size(16, 16)
                textureId = source.textureId
                videoEffect = com.haishinkit.graphics.effect.SepiaVideoEffect()
            }
        val renderer = com.haishinkit.gles.screen.Renderer(context, DynamicRangeMode.HLG)
        try {
            source.render {
                GLES20.glClearColor(1f, 1f, 1f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            quad.layout(renderer)
            destination.render {
                renderer.draw(quad)
                for (value in readFloatPixel().take(3)) org.junit.Assert.assertEquals(0.203f, value, 0.002f)
            }
        } finally {
            renderer.release()
            source.release()
            destination.release()
            graphics.close()
        }
    }

    @Test fun hlgInputCropRetainsFourDistinctCornersAndDoesNotDoubleFlip() {
        val graphics = GraphicsContext()
        graphics.open(null, DynamicRangeMode.HLG)
        graphics.makeCurrent(null)
        val source = Framebuffer().apply { bounds = Rect(0, 0, 64, 64) }
        val destination = Framebuffer(DynamicRangeMode.HLG).apply { bounds = Rect(0, 0, 16, 16) }
        val loader = ShaderLoader(InstrumentationRegistry.getInstrumentation().targetContext)
        val quad =
            com.haishinkit.screen.VideoScreenObject(target = GLES20.GL_TEXTURE_2D).apply {
                frame = Rect(0, 0, 16, 16)
                videoSize = android.util.Size(16, 16)
                textureId = source.textureId
                layout(com.haishinkit.screen.NullRenderer.SHARED)
                matrix[5] = -1f // The existing external-video geometry flip.
            }
        val crop =
            FloatArray(16).apply {
                android.opengl.Matrix.setIdentityM(this, 0)
                this[0] = 0.5f
                this[5] = -0.5f
                this[12] = 0.25f
                this[13] = 0.75f
            }
        try {
            source.render {
                GLES20.glClearColor(1f, 0f, 1f, 1f) // Padding must never reach the output.
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                for ((rect, rgb) in listOf(
                    Rect(16, 16, 32, 32) to floatArrayOf(1f, 0f, 0f),
                    Rect(32, 16, 48, 32) to floatArrayOf(0f, 1f, 0f),
                    Rect(16, 32, 32, 48) to floatArrayOf(0f, 0f, 1f),
                    Rect(32, 32, 48, 48) to floatArrayOf(1f, 1f, 1f),
                )) {
                    GLES20.glScissor(rect.left, rect.top, rect.width(), rect.height())
                    GLES20.glClearColor(rgb[0], rgb[1], rgb[2], 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
            val program = loader.getProgram(GLES20.GL_TEXTURE_2D, DefaultVideoEffect.shared, 1)!!
            destination.render {
                GLES20.glViewport(0, 0, 16, 16)
                program.use()
                program.draw(quad, textureTransform = crop)
                for ((point, rgb) in listOf(
                    (1 to 1) to floatArrayOf(1f, 0f, 0f),
                    (14 to 1) to floatArrayOf(0f, 1f, 0f),
                    (1 to 14) to floatArrayOf(0f, 0f, 1f),
                    (14 to 14) to floatArrayOf(1f, 1f, 1f),
                )) {
                    val actual = readFloatPixel(point.first, point.second)
                    val expected = HlgColor.fromSrgb(rgb[0], rgb[1], rgb[2])
                    for (channel in 0..2) org.junit.Assert.assertEquals("corner=$point channel=$channel", expected[channel], actual[channel], 0.002f)
                }
                // A cached program reused without a transform must restore the old UVs.
                program.use()
                program.draw(quad)
                val padding = readFloatPixel(1, 1)
                val expected = HlgColor.fromSrgb(1f, 0f, 1f)
                for (channel in 0..2) org.junit.Assert.assertEquals(expected[channel], padding[channel], 0.002f)
            }
        } finally {
            loader.release()
            destination.release()
            source.release()
            graphics.close()
        }
    }

    @Test fun failedHlgFramebufferResizeDisablesDrawingAndRecovers() {
        val graphics = GraphicsContext()
        graphics.open(null, DynamicRangeMode.HLG)
        graphics.makeCurrent(null)
        val framebuffer = Framebuffer(DynamicRangeMode.HLG)
        try {
            framebuffer.bounds = Rect(0, 0, 16, 16)
            org.junit.Assert.assertTrue(framebuffer.isEnabled)
            framebuffer.bounds = Rect()
            org.junit.Assert.assertFalse(framebuffer.isEnabled)
            val maximum = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0)
            framebuffer.bounds = Rect(0, 0, maximum[0] + 1, 16)
            org.junit.Assert.assertFalse(framebuffer.isEnabled)
            framebuffer.render { org.junit.Assert.fail("Invalid framebuffer must not draw") }
            framebuffer.bounds = Rect(0, 0, 16, 16)
            org.junit.Assert.assertTrue(framebuffer.isEnabled)
            framebuffer.render {
                GLES20.glClearColor(0.203f, 0.203f, 0.203f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                org.junit.Assert.assertEquals(0.203f, readFloatPixel()[0], 0.002f)
            }
        } finally {
            framebuffer.release()
            graphics.close()
        }
    }

    @Test fun encodeTenBitHlgOnBothInstalledHardwareEncoders() {
        for ((mime, profile) in listOf("video/hevc" to 2, "video/av01" to 2)) {
            val format =
                android.media.MediaFormat.createVideoFormat(mime, 320, 180).apply {
                    setInteger("profile", profile)
                    setInteger("color-format", android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger("bitrate", 500_000)
                    setInteger("frame-rate", 30)
                    setInteger("i-frame-interval", 1)
                    setInteger("color-standard", 6)
                    setInteger("color-transfer", 7)
                    setInteger("color-range", 2)
                }
            val info =
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    info.isEncoder && mime in info.supportedTypes &&
                        info.getCapabilitiesForType(mime).let {
                            (it.isFeatureSupported("hdr-editing") || it.isFeatureSupported("hlg-editing")) && it.isFormatSupported(format)
                        }
                }
            org.junit.Assume.assumeNotNull(info)
            val encoder = android.media.MediaCodec.createByCodecName(info!!.name)
            val graphics = GraphicsContext()
            var surface: android.view.Surface? = null
            val output = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, if (mime == "video/hevc") "hlg.h265" else "hlg.obu")
            try {
                encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder.createInputSurface()
                graphics.open(null, DynamicRangeMode.HLG)
                graphics.makeCurrent(graphics.createWindowSurface(surface, true))
                encoder.start()
                java.io.FileOutputStream(output).use { file ->
                    val bufferInfo = android.media.MediaCodec.BufferInfo()
                    var frames = 0
                    var changed = false
                    for (frame in 0..30) {
                        if (frame < 30) {
                            GLES20.glClearColor(0.75f, 0.75f, 0.75f, 1f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            graphics.setPresentationTime(frame * 33_333_333L)
                            assertTrue(graphics.swapBuffers())
                        } else {
                            encoder.signalEndOfInputStream()
                        }
                        var attempts = 0
                        while (attempts++ < 50) {
                            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                            if (index == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                changed = true
                                val actual = encoder.outputFormat
                                org.junit.Assert.assertEquals(6, actual.getInteger("color-standard"))
                                org.junit.Assert.assertEquals(7, actual.getInteger("color-transfer"))
                                Log.i("HlgRenderingTest", "ENCODED $mime $actual")
                            } else if (index >= 0) {
                                val buffer =
                                    encoder.getOutputBuffer(index)!!.duplicate().apply {
                                        position(bufferInfo.offset)
                                        limit(bufferInfo.offset + bufferInfo.size)
                                    }
                                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                file.write(bytes)
                                if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    Log.i("HlgRenderingTest", "CSD $mime ${bytes.joinToString("") { "%02x".format(it) }}")
                                } else if (bytes.isNotEmpty()) {
                                    frames++
                                }
                                val end = bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                encoder.releaseOutputBuffer(index, false)
                                if (end) break
                            } else if (frame < 30) {
                                break
                            }
                        }
                    }
                    assertTrue(changed)
                    assertTrue("No coded frames for $mime", frames > 0)
                }
                encoder.stop()
            } finally {
                graphics.close()
                surface?.release()
                encoder.release()
            }
        }
    }

    @Test fun compileHlgProgramsAndAllocateFloatComposition() {
        val graphics = GraphicsContext()
        try {
            graphics.open(null, DynamicRangeMode.HLG)
            graphics.makeCurrent(null)
            Log.i("HlgRenderingTest", "EGL=" + EGL14.eglQueryString(EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY), EGL14.EGL_EXTENSIONS))
            Log.i("HlgRenderingTest", "GL=" + GLES20.glGetString(GLES20.GL_EXTENSIONS))
            val loader = ShaderLoader(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                for (conversion in listOf(1, 2, 3, 4)) {
                    val target = if (conversion == 2 || conversion == 4) 0x8D65 else GLES20.GL_TEXTURE_2D
                    val program = loader.getProgram(target, DefaultVideoEffect.shared, conversion)
                    assertNotNull(program)
                    program!!.use()
                    assertTrue(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                }
                loader.getProgram(GLES20.GL_TEXTURE_2D, BicubicVideoEffect(), 3)!!.use()
                val framebuffer = Framebuffer(DynamicRangeMode.HLG)
                try {
                    framebuffer.bounds = Rect(0, 0, 64, 64)
                    framebuffer.render { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) }
                } finally {
                    framebuffer.release()
                }
            } finally {
                loader.release()
            }
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }) {
                for (mime in info.supportedTypes.filter { it == "video/hevc" || it == "video/av01" }) {
                    val cap = info.getCapabilitiesForType(mime)
                    Log.i("HlgRenderingTest", "${info.name} $mime HLG=${cap.isFeatureSupported("hlg-editing")} HDR=${cap.isFeatureSupported("hdr-editing")} profiles=${cap.profileLevels.map { it.profile }}")
                }
            }
        } finally {
            graphics.close()
        }
    }

    @Test fun failedSurfaceAttachmentDoesNotKillTheWorkerAndCanRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (mode in listOf(DynamicRangeMode.SDR, DynamicRangeMode.HLG)) {
            val screen = com.haishinkit.screen.Screen.createOpenGL(context, mode).apply { frame = Rect(0, 0, 16, 16) }
            val transform = ThreadPixelTransform(context)
            val brokenTexture = android.graphics.SurfaceTexture(false)
            val broken = android.view.Surface(brokenTexture).apply { release() }
            val replacementTexture = android.graphics.SurfaceTexture(false)
            val replacement = android.view.Surface(replacementTexture)
            try {
                transform.frameRate = 1
                transform.imageExtent = android.util.Size(16, 16)
                transform.surface = broken
                transform.screen = screen
                val handler = ThreadPixelTransform::class.java.getDeclaredMethod("getHandler").apply { isAccessible = true }.invoke(transform) as android.os.Handler
                val actual = ThreadPixelTransform::class.java.getDeclaredMethod("getPixelTransform").apply { isAccessible = true }.invoke(transform) as PixelTransform

                fun running(): Boolean {
                    val state = java.util.concurrent.FutureTask { actual.isRunning.get() }
                    handler.post(state)
                    return state.get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                org.junit.Assert.assertFalse(running())
                org.junit.Assert.assertTrue(handler.looper.thread.isAlive)
                transform.surface = replacement
                org.junit.Assert.assertTrue(running())
            } finally {
                transform.close()
                screen.dispose()
                brokenTexture.release()
                replacement.release()
                replacementTexture.release()
            }
        }
    }

    private fun readFloatPixel(
        x: Int = 8,
        y: Int = 8,
    ): FloatArray {
        val buffer = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, buffer)
        org.junit.Assert.assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        return FloatArray(4) { buffer[it] }
    }
}
