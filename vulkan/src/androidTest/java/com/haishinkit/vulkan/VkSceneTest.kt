package com.haishinkit.vulkan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.media.ImageWriter
import android.os.Build
import android.util.Size
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.LanczosVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VkSceneTest {
    private class TintEffect(var red: Float) : VideoEffect {
        override val name = "test-tint"
    }

    private class MissingEffect : VideoEffect {
        override val name = "missing"
    }

    @Test
    fun bitmapOrientationAlphaAndTextComposeWithoutOpenGLFactory() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldFactory = PixelTransform.factory
        PixelTransform.factory = { error("Vulkan rendering must not create an OpenGL producer") }
        val screen =
            VkScreen.create(context).apply {
                frame = Rect(0, 0, 64, 64)
                backgroundColor = Color.RED
            }
        try {
            screen.addChild(
                ImageScreenObject().apply {
                    frame = Rect(0, 0, 32, 32)
                    bitmap =
                        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.GREEN)
                            setPixel(8, 8, Color.BLUE)
                        }
                },
            )
            screen.addChild(
                ImageScreenObject().apply {
                    frame = Rect(32, 0, 48, 16)
                    bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0x8000ff00.toInt()) }
                },
            )
            screen.addChild(
                TextScreenObject().apply {
                    frame = Rect(0, 40, 64, 64)
                    value = "Hi"
                    size = 20f
                },
            )
            val image = snapshot(screen)
            assertEquals(Color.BLUE, image.getPixel(8, 8))
            assertEquals(Color.GREEN, image.getPixel(8, 24))
            val blended = image.getPixel(40, 8)
            assertTrue(abs(Color.red(blended) - 127) <= 2 && abs(Color.green(blended) - 128) <= 2)
            assertEquals(Color.RED, image.getPixel(55, 24))
            assertTrue((40 until 64).any { y -> (0 until 40).any { x -> Color.green(image.getPixel(x, y)) > 100 } })
            image.recycle()
        } finally {
            screen.dispose()
            PixelTransform.factory = oldFactory
        }
    }

    @Test
    fun registeredSpirvUsesChangingParametersAndMissingShaderFailsExplicitly() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = VkScreen.create(context).apply { frame = Rect(0, 0, 16, 16) }
        val effect = TintEffect(0.25f)
        VulkanEffectRegistry.register(TintEffect::class.java) {
            VulkanShader("shaders/example-tint.frag.spv", parameters = floatArrayOf(it.red, 1f, 1f))
        }
        val objectToDraw =
            ImageScreenObject().apply {
                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                videoEffect = effect
            }
        screen.addChild(objectToDraw)
        try {
            snapshot(screen).also {
                assertTrue(abs(Color.red(it.getPixel(8, 8)) - 64) <= 2)
                it.recycle()
            }
            effect.red = 0.75f
            snapshot(screen).also {
                assertTrue(abs(Color.red(it.getPixel(8, 8)) - 191) <= 2)
                it.recycle()
            }
            objectToDraw.videoEffect = MissingEffect()
            assertThrows(IllegalStateException::class.java) { snapshot(screen) }
            VulkanEffectRegistry.register(MissingEffect::class.java) { VulkanShader("shaders/missing.frag.spv") }
            assertThrows(IllegalStateException::class.java) { snapshot(screen) }
            assertThrows(IllegalStateException::class.java) { snapshot(screen) } // Retry must fail safely too.
            objectToDraw.videoEffect = effect
            snapshot(screen).recycle() // The renderer remains usable after the failed transaction.
        } finally {
            VulkanEffectRegistry.unregister(TintEffect::class.java)
            VulkanEffectRegistry.unregister(MissingEffect::class.java)
            screen.dispose()
        }
    }

    @Test
    fun videoInputHardwareBufferReachesCompositionAndDetaches() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = VkScreen.create(context).apply { frame = Rect(0, 0, 64, 64) }
        val source =
            object : VideoSource {
                override var surface: Surface? = null
                override val videoSize = Size(64, 64)
                override val imageOrientation = ImageOrientation.UP

                override suspend fun open(mixer: MediaMixer) = Result.success(Unit)

                override suspend fun close() = Result.success(Unit)
            }
        try {
            screen.addChild(VideoScreenObject())
            screen.attachVideo(0, source)
            val surface = checkNotNull(source.surface)
            val canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.BLUE)
            canvas.drawRect(0f, 0f, 32f, 32f, Paint().apply { color = Color.GREEN })
            surface.unlockCanvasAndPost(canvas)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var matched = false
            while (!matched && System.nanoTime() < deadline) {
                snapshot(screen).also {
                    matched = Color.green(it.getPixel(8, 8)) > 200 && Color.blue(it.getPixel(48, 48)) > 200
                    it.recycle()
                }
            }
            assertTrue("Video input did not reach Vulkan composition", matched)
            screen.attachVideo(0, null)
            assertEquals(null, source.surface)
        } finally {
            screen.dispose()
        }
    }

    @Test
    fun yuvInputUsesConversionAndCropRectangle() {
        assumeTrue(VkPixelTransform.isSupported())
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = VkScreen.create(context).apply { frame = Rect(0, 0, 64, 64) }
        val source =
            object : VideoSource {
                override var surface: Surface? = null
                override val videoSize = Size(64, 64)
                override val imageOrientation = ImageOrientation.UP
                override val videoCropRect = Rect(16, 0, 48, 64)

                override suspend fun open(mixer: MediaMixer) = Result.success(Unit)

                override suspend fun close() = Result.success(Unit)
            }
        var writer: ImageWriter? = null
        try {
            val video = VideoScreenObject()
            screen.addChild(video)
            screen.attachVideo(0, source)
            writer = ImageWriter.newInstance(checkNotNull(source.surface), 3, ImageFormat.YUV_420_888)
            val input = writer.dequeueInputImage()
            // Black outer bands would be visible if the imported source crop were ignored.
            input.planes.forEachIndexed { index, plane ->
                val width = if (index == 0) 64 else 32
                val height = if (index == 0) 64 else 32
                for (y in 0 until height) for (x in 0 until width) {
                    val value =
                        if (index != 0) {
                            128
                        } else if (x < 16 || x >= 48) {
                            16
                        } else if (x < 32) {
                            80
                        } else {
                            200
                        }
                    plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                }
            }
            input.cropRect = Rect(16, 0, 48, 64)
            input.timestamp = System.nanoTime()
            writer.queueInputImage(input)
            listOf(
                DefaultVideoEffect.shared,
                BicubicVideoEffect(),
                LanczosVideoEffect().apply {
                    texelWidth = 64f
                    texelHeight = 64f
                },
            ).forEach { effect ->
                video.videoEffect = effect
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var matched = false
                var left = 0
                var right = 0
                var borderDifference = Int.MAX_VALUE
                while (!matched && System.nanoTime() < deadline) {
                    snapshot(screen).also { image ->
                        val a = image.getPixel(8, 32)
                        val b = image.getPixel(56, 32)
                        left = Color.red(a)
                        right = Color.red(b)
                        borderDifference =
                            maxOf(
                                abs(Color.red(image.getPixel(0, 32)) - left),
                                abs(Color.red(image.getPixel(1, 32)) - left),
                                abs(Color.red(image.getPixel(62, 32)) - right),
                                abs(Color.red(image.getPixel(63, 32)) - right),
                            )
                        matched = left in 55..100 && right in 180..225 && borderDifference <= 3 &&
                            abs(Color.green(a) - left) <= 4 && abs(Color.blue(a) - left) <= 4 &&
                            abs(Color.green(b) - right) <= 4 && abs(Color.blue(b) - right) <= 4
                        image.recycle()
                    }
                }
                assertTrue("${effect.name} crop border mismatch: left=$left right=$right difference=$borderDifference", matched)
            }
        } finally {
            writer?.close()
            screen.dispose()
        }
    }

    @Test
    fun cameraSensorQuarterTurnsPreserveTopAndBottom() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = VkScreen.create(context).apply { frame = Rect(0, 0, 64, 64) }
        val source =
            object : VideoSource {
                override var surface: Surface? = null
                override val videoSize = Size(64, 64)
                override var imageOrientation = ImageOrientation.LEFT

                override suspend fun open(mixer: MediaMixer) = Result.success(Unit)

                override suspend fun close() = Result.success(Unit)
            }
        try {
            screen.addChild(VideoScreenObject())
            screen.attachVideo(0, source)
            val surface = checkNotNull(source.surface)
            val canvas = surface.lockHardwareCanvas()
            val paint = Paint()
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
                paint.color = color
                val x = (index % 2) * 32f
                val y = (index / 2) * 32f
                canvas.drawRect(x, y, x + 32f, y + 32f, paint)
            }
            surface.unlockCanvasAndPost(canvas)
            // Match the existing OpenGL Screen convention for sensor orientations LEFT/RIGHT.
            val cases =
                listOf(
                    ImageOrientation.LEFT to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                    ImageOrientation.RIGHT to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
                    ImageOrientation.UP to listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                )
            cases.forEach { (orientation, expected) ->
                source.imageOrientation = orientation
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var actual = emptyList<Int>()
                do {
                    snapshot(screen).also { image ->
                        actual = listOf(image.getPixel(8, 8), image.getPixel(56, 8), image.getPixel(8, 56), image.getPixel(56, 56))
                        image.recycle()
                    }
                } while (actual != expected && System.nanoTime() < deadline)
                assertEquals("Camera orientation $orientation", expected, actual)
            }
        } finally {
            screen.dispose()
        }
    }

    @Test
    fun failedSurfaceAttachmentClosesReaderEvenWhenDetachingThrows() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = VkScreen.create(context)
        val attachError = IllegalStateException("producer rejected surface")
        val detachError = IllegalStateException("producer rejected detach")
        var assigned: Surface? = null
        val source =
            object : VideoSource {
                override var surface: Surface?
                    get() = assigned
                    set(value) {
                        if (value == null) throw detachError
                        assigned = value
                        throw attachError
                    }
                override val videoSize = Size(64, 64)
                override val imageOrientation = ImageOrientation.UP

                override suspend fun open(mixer: MediaMixer) = Result.success(Unit)

                override suspend fun close() = Result.success(Unit)
            }
        try {
            val thrown = assertThrows(IllegalStateException::class.java) { screen.attachVideo(0, source) }
            assertSame(attachError, thrown)
            assertTrue(thrown.suppressed.contains(detachError))
            assertFalse("The failed attachment retained its input Surface", checkNotNull(assigned).isValid)
            screen.attachVideo(0, null) // No partially attached entry remains in the registry.
        } finally {
            screen.dispose()
        }
    }

    private fun snapshot(screen: VkScreen): Bitmap {
        var result: Bitmap? = null
        screen.readPixels { result = it }
        assertNotNull(result)
        return checkNotNull(result)
    }
}
