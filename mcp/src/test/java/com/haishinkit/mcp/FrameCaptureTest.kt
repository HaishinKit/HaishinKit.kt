package com.haishinkit.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameCaptureTest {
    private val capture = FrameCapture()

    @Test
    fun encodesJpegAndPreservesAspectRatioWithoutUpscaling() =
        runBlocking {
            val owner = Any()
            val screen =
                TestScreen { callback ->
                    callback(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
                }
            capture.attach(owner, screen)
            try {
                val image = capture.capture(320, 85)
                val bytes = Base64.decode(image.data, Base64.DEFAULT)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(320, image.width)
                assertEquals(180, image.height)
                assertEquals(320, decoded.width)
                assertTrue(Color.red(decoded.getPixel(10, 10)) > 240)
                decoded.recycle()
                assertEquals(640, capture.capture(1280, 85).width)
            } finally {
                capture.detach(owner)
            }
        }

    @Test
    fun rejectsFrameWhenPublishStopsDuringReadback() =
        runBlocking {
            val callback = CompletableDeferred<(Bitmap?) -> Unit>()
            val owner = Any()
            capture.attach(owner, TestScreen { callback.complete(it) })
            try {
                val pending = async { runCatching { capture.capture(320, 85) } }
                val complete = callback.await()
                capture.detach(owner)
                val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
                complete(bitmap)
                assertTrue(pending.await().isFailure)
                assertTrue(bitmap.isRecycled)
                assertTrue(runCatching { capture.capture(320, 85) }.isFailure)
            } finally {
                capture.detach(owner)
            }
        }

    @Test
    fun recyclesLateBitmapAfterRequestCancellation() =
        runBlocking {
            val callback = CompletableDeferred<(Bitmap?) -> Unit>()
            val owner = Any()
            capture.attach(owner, TestScreen { callback.complete(it) })
            try {
                val pending = async { capture.capture(320, 85) }
                val complete = callback.await()
                pending.cancelAndJoin()
                val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
                complete(bitmap)
                assertTrue(bitmap.isRecycled)
            } finally {
                capture.detach(owner)
            }
        }

    private class TestScreen(private val read: ((Bitmap?) -> Unit) -> Unit) : Screen(RuntimeEnvironment.getApplication()) {
        override fun readPixels(lambda: (Bitmap?) -> Unit) = read(lambda)

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) = Unit

        override fun bind(screenObject: ScreenObject) = Unit

        override fun unbind(screenObject: ScreenObject) = Unit
    }
}
