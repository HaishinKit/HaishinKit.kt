package com.haishinkit.vulkan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.screen.ImageScreenObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class VkPixelTransformTest {
    private class BrokenPreviewEffect : VideoEffect {
        override val name = "broken-preview"
    }

    @Test
    fun downsampledCompositionMatchesBicubicAtVulkanOutput() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceSize = 128
        val outputSize = 85
        val values = IntArray(sourceSize) { (it * 37) % 256 }
        val expected =
            DoubleArray(outputSize) { x ->
                val position = (x + 0.5) * sourceSize / outputSize - 0.5
                (floor(position).toInt() - 1..floor(position).toInt() + 2).sumOf { index ->
                    values[index.coerceIn(0, sourceSize - 1)] * splineWeight(position - index)
                }
            }
        val screen = VkScreen.create(context).apply { frame = Rect(0, 0, sourceSize, sourceSize) }
        screen.addChild(
            ImageScreenObject().apply {
                frame = Rect(0, 0, sourceSize, sourceSize)
                bitmap =
                    Bitmap.createBitmap(sourceSize, sourceSize, Bitmap.Config.ARGB_8888).apply {
                        setPixels(
                            IntArray(sourceSize * sourceSize) { index ->
                                val value = values[index % sourceSize]
                                Color.rgb(value, value, value)
                            },
                            0, sourceSize, 0, 0, sourceSize, sourceSize,
                        )
                    }
            },
        )
        val reader = ImageReader.newInstance(outputSize, outputSize, PixelFormat.RGBA_8888, 3)
        val callbacks = HandlerThread("VulkanResamplingTest").apply { start() }
        val received = CountDownLatch(1)
        val lastError = AtomicReference(Double.POSITIVE_INFINITY)
        val transform = VkPixelTransform(context)
        try {
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val error =
                        expected.indices.maxOf { x ->
                            val offset = outputSize / 2 * plane.rowStride + x * plane.pixelStride
                            abs((plane.buffer.get(offset).toInt() and 255) - expected[x])
                        }
                    lastError.set(error)
                    if (error <= 4.0) received.countDown()
                }
            }, Handler(callbacks.looper))
            transform.imageExtent = Size(outputSize, outputSize)
            transform.screen = screen
            transform.surface = reader.surface
            val matched = received.await(10, TimeUnit.SECONDS)
            assertTrue(
                "Vulkan output must retain bicubic minification; max error ${lastError.get()}",
                matched,
            )
        } finally {
            transform.close()
            screen.dispose()
            reader.setOnImageAvailableListener(null, null)
            callbacks.quitSafely()
            callbacks.join()
            reader.close()
        }
    }

    @Test
    fun defaultResamplingUsesBicubic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VkPixelTransform(context).use { transform ->
            assertTrue(transform.videoEffect is BicubicVideoEffect)
        }
    }

    @Test
    fun compositionReachesOutputAfterSurfaceReattachment() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen =
            VkScreen.create(context).apply {
                frame = Rect(0, 0, 64, 64)
                backgroundColor = Color.RED
            }
        screen.addChild(
            ImageScreenObject().apply {
                frame = Rect(0, 0, 16, 16)
                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            },
        )
        val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3)
        val callbackThread = HandlerThread("VulkanTestReader").apply { start() }
        val transform = VkPixelTransform(context)
        try {
            transform.imageExtent = Size(64, 64)
            transform.screen = screen
            repeat(2) {
                val received = CountDownLatch(1)
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val offset = 32 * plane.rowStride + 32 * plane.pixelStride
                        val red = plane.buffer.get(offset).toInt() and 0xff
                        val green = plane.buffer.get(offset + 1).toInt() and 0xff
                        val corner = 8 * plane.rowStride + 8 * plane.pixelStride
                        val cornerGreen = plane.buffer.get(corner + 1).toInt() and 0xff
                        if (red > 200 && green < 30 && cornerGreen > 200 && image.timestamp > 0) received.countDown()
                    }
                }, Handler(callbackThread.looper))
                transform.surface = reader.surface
                assertTrue("The red Screen composition did not reach Vulkan output", received.await(10, TimeUnit.SECONDS))
                transform.surface = null
            }
        } finally {
            transform.close()
            screen.dispose()
            reader.setOnImageAvailableListener(null, null)
            callbackThread.quitSafely()
            callbackThread.join()
            reader.close()
        }
    }

    @Test
    fun codecOutputHasIncreasingPresentationTimestamps() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val transform = VkPixelTransform(context)
        var input: android.view.Surface? = null
        val screen = VkScreen.create(context).apply { backgroundColor = Color.GREEN }
        try {
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 128, 128).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            encoder.start()
            transform.imageExtent = Size(128, 128)
            transform.frameRate = 30
            transform.screen = screen
            transform.surface = input
            val info = MediaCodec.BufferInfo()
            val timestamps = mutableListOf<Long>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (timestamps.size < 5 && System.nanoTime() < deadline) {
                val index = encoder.dequeueOutputBuffer(info, 100_000)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        timestamps.add(info.presentationTimeUs)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("Vulkan did not produce encoded frames", timestamps.size >= 5)
            assertTrue(timestamps.zipWithNext().all { (first, second) -> second > first })
        } finally {
            transform.close()
            screen.dispose()
            encoder.release()
            input?.release()
        }
    }

    @Test
    fun failedPreviewShaderDoesNotBlockIndependentEncoder() {
        assumeTrue(VkPixelTransform.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen =
            VkScreen.create(context).apply {
                frame = Rect(0, 0, 64, 64)
                backgroundColor = Color.GREEN
            }
        val errors = AtomicInteger()
        screen.onError = { errors.incrementAndGet() }
        val previewReader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3)
        val preview = VkPixelTransform(context)
        val output = VkPixelTransform(context)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var input: android.view.Surface? = null
        VulkanEffectRegistry.register(BrokenPreviewEffect::class.java) { VulkanShader("shaders/nonexistent-preview.frag.spv") }
        try {
            // Register the failing output first; the encoder must still be rendered every frame.
            preview.videoEffect = BrokenPreviewEffect()
            preview.imageExtent = Size(64, 64)
            preview.screen = screen
            preview.surface = previewReader.surface
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 128, 128).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            encoder.start()
            output.imageExtent = Size(128, 128)
            output.frameRate = 30
            output.screen = screen
            output.surface = input
            val info = MediaCodec.BufferInfo()
            var frames = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (frames < 5 && System.nanoTime() < deadline) {
                val index = encoder.dequeueOutputBuffer(info, 100_000)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) frames++
                    encoder.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("The broken preview prevented encoder output", frames >= 5)
            assertTrue("The failing preview should still report its shader error", errors.get() > 0)
        } finally {
            preview.close()
            output.close()
            screen.dispose()
            encoder.release()
            input?.release()
            previewReader.close()
            VulkanEffectRegistry.unregister(BrokenPreviewEffect::class.java)
        }
    }

    private fun splineWeight(distance: Double): Double {
        val x = abs(distance)
        return when {
            x < 1 -> (4 - 6 * x * x + 3 * x * x * x) / 6
            x < 2 -> (2 - x) * (2 - x) * (2 - x) / 6
            else -> 0.0
        }
    }
}
