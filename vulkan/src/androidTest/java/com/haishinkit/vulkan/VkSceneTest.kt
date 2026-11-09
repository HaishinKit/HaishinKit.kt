package com.haishinkit.vulkan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun snapshot(screen: VkScreen): Bitmap {
        var result: Bitmap? = null
        screen.readPixels { result = it }
        assertNotNull(result)
        return checkNotNull(result)
    }
}
