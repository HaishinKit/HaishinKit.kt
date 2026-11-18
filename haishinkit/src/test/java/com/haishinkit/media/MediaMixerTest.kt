package com.haishinkit.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.MockScreen
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.VideoScreenObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class MediaMixerTest {
    @Test
    fun startupRefreshesDefaultVideoBeforeQueuedScreenInsertion() {
        var queuedChild: ScreenObject? = null
        withMixer(
            running = false,
            screenFactory = { context ->
                object : Screen(context) {
                    override fun addChild(child: ScreenObject?) { queuedChild = child }
                    override fun attachVideo(track: Int, video: VideoSource?) {}
                    override fun readPixels(lambda: (Bitmap?) -> Unit) {}
                    override fun bind(screenObject: ScreenObject) {}
                    override fun unbind(screenObject: ScreenObject) {}
                }
            },
        ) { mixer ->
            val context = RuntimeEnvironment.getApplication()
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val originalRotation = display.rotation
            try {
                shadowOf(display).setRotation(Surface.ROTATION_0)
                mixer.attachVideo(0, TestSource()).getOrThrow()
                shadowOf(display).setRotation(Surface.ROTATION_90)
                mixer.startRunning()
                val video = (checkNotNull(queuedChild) as ScreenObjectContainer).findByClass(VideoScreenObject::class.java).single()
                assertEquals(Surface.ROTATION_90, video.deviceOrientation)
            } finally {
                mixer.stopRunning()
                shadowOf(display).setRotation(originalRotation)
            }
        }
    }

    @Test
    fun defaultVideoFollowsDisplayRotationWithoutChangingCustomObjects() =
        withMixer(running = false) { mixer ->
            val context = RuntimeEnvironment.getApplication()
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val originalRotation = display.rotation
            try {
                shadowOf(display).setRotation(Surface.ROTATION_90)
                mixer.attachVideo(0, Camera2Source(context)).getOrThrow()
                val video = mixer.screen.findByClass(VideoScreenObject::class.java).single()
                assertTrue(video.isRotatesWithContent)
                assertEquals(Surface.ROTATION_90, video.deviceOrientation)
                assertEquals(ImageOrientation.UP, video.imageOrientation)

                val custom = VideoScreenObject()
                mixer.screen.addChild(custom)
                assertFalse(custom.isRotatesWithContent)
                mixer.attachVideo(0, TestSource()).getOrThrow()
                assertFalse(video.isRotatesWithContent)
                mixer.attachVideo(0, Camera2Source(context)).getOrThrow()
                assertTrue(video.isRotatesWithContent)
                mixer.attachVideo(0, null).getOrThrow()
                assertFalse(video.isRotatesWithContent)
            } finally {
                shadowOf(display).setRotation(originalRotation)
            }
        }

    @Test
    fun failedReplacementKeepsVideoAvailableAndReopensPreviousSource() =
        withMixer { mixer ->
            val original = TestSource()
            mixer.attachVideo(0, original).getOrThrow()

            val opening = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Result<Unit>>()
            val replacement =
                TestSource {
                    opening.complete(Unit)
                    finish.await()
                }
            val result = async { mixer.attachVideo(0, replacement) }
            opening.await()
            assertTrue(mixer.hasVideo)

            val failure = IllegalStateException("Could not open camera")
            finish.complete(Result.failure(failure))
            assertSame(failure, result.await().exceptionOrNull())
            assertTrue(mixer.hasVideo)
            assertEquals(2, original.openCount)
            assertEquals(1, replacement.closeCount)
        }

    @Test
    fun cancelledReplacementClosesNewSourceAndReopensPreviousSource() =
        withMixer { mixer ->
            val original = TestSource()
            mixer.attachVideo(0, original).getOrThrow()

            val opening = CompletableDeferred<Unit>()
            val replacement =
                TestSource {
                    opening.complete(Unit)
                    awaitCancellation()
                }
            val job = launch { mixer.attachVideo(0, replacement) }
            opening.await()
            job.cancelAndJoin()

            assertTrue(mixer.hasVideo)
            assertEquals(2, original.openCount)
            assertEquals(1, replacement.closeCount)
        }

    private fun withMixer(
        running: Boolean = true,
        screenFactory: (Context) -> Screen = { MockScreen(it) },
        block: suspend CoroutineScope.(MediaMixer) -> Unit,
    ) {
        val original = Screen.factory
        try {
            Screen.factory = screenFactory
            val mixer = MediaMixer(RuntimeEnvironment.getApplication(), AtomicBoolean(running))
            runBlocking {
                try {
                    withTimeout(5000) { block(mixer) }
                } finally {
                    mixer.attachVideo(0, null).getOrThrow()
                }
            }
        } finally {
            Screen.factory = original
        }
    }

    private class TestSource(private val onOpen: suspend () -> Result<Unit> = { Result.success(Unit) }) : VideoSource {
        override var surface: Surface? = null
        override val videoSize = Size(16, 16)
        override val imageOrientation = ImageOrientation.UP
        var openCount = 0
            private set
        var closeCount = 0
            private set

        override suspend fun open(mixer: MediaMixer): Result<Unit> {
            openCount++
            return onOpen()
        }

        override suspend fun close(): Result<Unit> {
            closeCount++
            return Result.success(Unit)
        }
    }
}
