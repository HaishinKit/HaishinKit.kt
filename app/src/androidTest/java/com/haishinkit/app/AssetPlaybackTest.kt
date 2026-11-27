package com.haishinkit.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haishinkit.media.MediaMixer
import com.haishinkit.screen.AssetScreenObject
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.vulkan.VkScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real MediaExtractor/MediaCodec and renderer tests. No camera, microphone or server is used. */
@RunWith(AndroidJUnit4::class)
class AssetPlaybackTest {
    @Test fun openGlDecodesLoopsAndDetachesTheVideo() = exercise(false, true, "asset-short.mp4")

    @Test fun vulkanDecodesLoopsAndDetachesTheVideo() = exercise(true, true, "asset-short.mp4")

    @Test fun openGlStopsAtEndOfNonLoopingVideo() = exercise(false, false, "asset-short.mp4")

    @Test fun vulkanStopsAtEndOfNonLoopingVideo() = exercise(true, false, "asset-short.mp4")

    @Test fun openGlReadsRotatedVideo() = exercise(false, true, "asset-rotated.mp4")

    @Test fun openGlReadsUnrotatedQuadrants() = exercise(false, true, "asset-colors.mp4")

    @Test fun vulkanReadsUnrotatedQuadrants() = exercise(true, true, "asset-colors.mp4")

    @Test fun openGlReadsHalfTurnVideo() = exercise(false, true, "asset-180.mp4")

    @Test fun vulkanReadsHalfTurnVideo() = exercise(true, true, "asset-180.mp4")

    @Test fun openGlReadsThreeQuarterTurnVideo() = exercise(false, true, "asset-270.mp4")

    @Test fun vulkanReadsThreeQuarterTurnVideo() = exercise(true, true, "asset-270.mp4")

    @Test fun vulkanReadsRotatedVideo() = exercise(true, true, "asset-rotated.mp4")

    @Test fun openGlCanCancelAndRestartPlayback() = exercise(false, true, "asset-short.mp4", true)

    @Test fun vulkanCanCancelAndRestartPlayback() = exercise(true, true, "asset-short.mp4", true)

    @Test fun drawingCanQueueSceneMutationsWithoutChangingTheLiveTraversal() {
        val screen = Screen.createOpenGL(InstrumentationRegistry.getInstrumentation().targetContext)
        screen.frame = Rect(0, 0, 64, 48)
        val observerFrames = java.util.concurrent.atomic.AtomicInteger()
        val marker =
            object : com.haishinkit.screen.ScreenObject("marker") {
                override val type = "test"
                override var elements: Map<String, String> = emptyMap()

                override fun draw(renderer: com.haishinkit.screen.Renderer) {}
            }
        val modifier =
            object : com.haishinkit.screen.ScreenObject("modifier") {
                override val type = "test"
                override var elements: Map<String, String> = emptyMap()
                private var queued = false

                override fun draw(renderer: com.haishinkit.screen.Renderer) {
                    if (queued) return
                    queued = true
                    screen.addChild(marker)
                    screen.removeChild(this)
                }
            }
        val observer =
            object : com.haishinkit.screen.ScreenObject("observer") {
                override val type = "test"
                override var elements: Map<String, String> = emptyMap()

                override fun draw(renderer: com.haishinkit.screen.Renderer) {
                    observerFrames.incrementAndGet()
                }
            }
        try {
            screen.addChild(modifier)
            screen.addChild(observer)
            await(5_000) {
                pixels(screen).recycle()
                observerFrames.get() >= 2 && screen.findById("marker") === marker
            }
        } finally {
            screen.dispose()
        }
    }

    @Test fun assetOnlyMixerObservesQueuedAddAndRemovalBeforeVideoSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "queued-asset.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("asset-short.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val mixer = MediaMixer(context)
        val asset = AssetScreenObject(context).apply { isLooping = true }
        try {
            // Keep the owner busy so addChild cannot complete before hasVideo is called.
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            mixer.screen.readPixels { bitmap ->
                bitmap?.recycle()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            asset.startReading(Uri.fromFile(file))
            val container = com.haishinkit.screen.ScreenObjectContainer().apply { addChild(asset) }
            mixer.screen.addChild(container)
            val result = CompletableFuture.supplyAsync { mixer.hasVideo }
            Thread.sleep(50)
            assertFalse("Video selection must wait for preceding scene additions", result.isDone)
            release.countDown()
            assertTrue(AssetScreenObject.findAssets(mixer.screen).contains(asset))
            assertTrue(result.get(5, TimeUnit.SECONDS))
            mixer.screen.removeChild(container)
            assertFalse(mixer.hasVideo)
        } finally {
            asset.close()
            mixer.dispose()
            file.delete()
        }
    }

    private fun exercise(
        vulkan: Boolean,
        loop: Boolean,
        name: String,
        restart: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        val screen = if (vulkan) VkScreen.create(context) else Screen.createOpenGL(context)
        screen.frame = Rect(0, 0, 64, 48)
        if (vulkan) {
            try {
                pixels(screen).recycle()
            } catch (error: Exception) {
                screen.dispose()
                var cause: Throwable? = error
                while (cause != null) {
                    if (cause is IllegalStateException && cause.message == "Required Vulkan features are unavailable") {
                        assumeNoException("Device lacks the required Vulkan features", cause)
                    }
                    cause = cause.cause
                }
                throw error
            }
        }
        val backgroundBitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val background =
            ImageScreenObject().apply {
                bitmap = backgroundBitmap
                frame = Rect(0, 0, 64, 48)
            }
        val error = AtomicReference<Throwable?>()
        if (screen is VkScreen) screen.onError = { error.set(it) }
        val asset =
            AssetScreenObject(context).apply {
                frame = Rect(0, 0, 64, 48)
                isLooping = loop
                onError = { error.set(it) }
                startReading(Uri.fromFile(file))
            }
        screen.addChild(background)
        screen.addChild(asset)
        try {
            var decoded = false
            await(10_000) {
                checkError(error)
                val image = pixels(screen)
                try {
                    decoded = hasVideoPixels(image)
                    decoded
                } finally {
                    image.recycle()
                }
            }
            assertTrue("No decoded video pixels ($vulkan)", decoded)
            val expectedCorners =
                when (name) {
                    "asset-colors.mp4" -> listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                    "asset-rotated.mp4" -> listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
                    "asset-180.mp4" -> listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED)
                    "asset-270.mp4" -> listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN)
                    else -> null
                }
            if (expectedCorners != null) {
                val image = pixels(screen)
                try {
                    listOf(22 to 12, 42 to 12, 22 to 36, 42 to 36).forEachIndexed { index, (x, y) ->
                        assertColor(image.getPixel(x, y), expectedCorners[index])
                    }
                } finally {
                    image.recycle()
                }
            }
            if (restart) {
                asset.cancelReading()
                await(5_000) {
                    val image = pixels(screen)
                    try {
                        (image.getPixel(32, 24) and 0xFFFFFF) == 0xFF00FF
                    } finally {
                        image.recycle()
                    }
                }
                asset.startReading(Uri.fromFile(file))
                await(5_000) {
                    checkError(error)
                    val image = pixels(screen)
                    try {
                        hasVideoPixels(image)
                    } finally {
                        image.recycle()
                    }
                }
            }
            if (loop) {
                var previous = asset.currentTimeUs
                var wrapped = false
                await(6_000) {
                    checkError(error)
                    val now = asset.currentTimeUs
                    if (previous >= 600_000 && now < 300_000) wrapped = true
                    previous = now
                    wrapped
                }
                assertTrue("The short video did not loop", wrapped)
                assertTrue(asset.isReading)
            } else {
                await(5_000) {
                    checkError(error)
                    !asset.isReading
                }
                assertFalse(asset.isReading)
                await(5_000) {
                    val image = pixels(screen)
                    try {
                        (image.getPixel(32, 24) and 0xFFFFFF) == 0xFF00FF
                    } finally {
                        image.recycle()
                    }
                }
            }
            screen.removeChild(asset)
            asset.close()
            await(5_000) {
                checkError(error)
                val image = pixels(screen)
                try {
                    (image.getPixel(32, 24) and 0xFFFFFF) == 0xFF00FF
                } finally {
                    image.recycle()
                }
            }
        } finally {
            asset.close()
            screen.dispose()
            file.delete()
            // The screen's asynchronous detach can still inspect the bitmap; allow GC to reclaim it.
        }
    }

    private fun assertColor(
        actual: Int,
        expected: Int,
    ) {
        val distance =
            kotlin.math.abs(Color.red(actual) - Color.red(expected)) +
                kotlin.math.abs(Color.green(actual) - Color.green(expected)) +
                kotlin.math.abs(Color.blue(actual) - Color.blue(expected))
        assertTrue("Unexpected corner color: ${Integer.toHexString(actual)}, expected ${Integer.toHexString(expected)}", distance < 100)
    }

    private fun checkError(error: AtomicReference<Throwable?>) {
        error.get()?.let { throw AssertionError("Asset render failed", it) }
    }

    private fun pixels(screen: Screen): Bitmap {
        val result = CompletableFuture<Bitmap>()
        screen.readPixels { bitmap -> if (bitmap != null) result.complete(bitmap) else result.completeExceptionally(AssertionError("No framebuffer")) }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun hasVideoPixels(image: Bitmap): Boolean {
        var min = 255
        var max = 0
        var nonMagenta = 0
        for (y in 4 until image.height - 4 step 4) for (x in 4 until image.width - 4 step 4) {
            val pixel = image.getPixel(x, y)
            if (pixel and 0xFFFFFF == 0xFF00FF) continue
            nonMagenta++
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            min = minOf(min, brightness)
            max = maxOf(max, brightness)
        }
        return nonMagenta > 20 && max - min > 25
    }

    private fun await(
        timeout: Long,
        condition: () -> Boolean,
    ) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            if (android.os.SystemClock.elapsedRealtime() >= end) throw AssertionError("Timed out waiting for video playback")
            Thread.sleep(20)
        }
    }
}
