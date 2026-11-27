package com.haishinkit.app

import android.graphics.Bitmap
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class SceneSnapshotWaitTest {
    @Test fun frameTimeoutIsAnOrdinaryFailureAndUnregistersTheCallback() =
        runBlocking {
            val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
            val screen = TestScreen()
            val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { store.captureNextFrame(screen, 100) } }
            assertEquals(1, screen.callbackCount)
            assertTrue(result.await().exceptionOrNull() is IllegalStateException)
            assertEquals(0, screen.callbackCount)
        }

    @Test fun leavingTheScreenStillCancelsAndUnregistersTheCallback() =
        runBlocking {
            val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
            val screen = TestScreen()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { store.captureNextFrame(screen, 5000) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(0, screen.callbackCount)
        }

    private class TestScreen : Screen(RuntimeEnvironment.getApplication()) {
        val callbackCount: Int get() = callbacks.size

        override fun readPixels(lambda: (Bitmap?) -> Unit) = lambda(null)

        override fun bind(screenObject: ScreenObject) {}

        override fun unbind(screenObject: ScreenObject) {}

        override fun attachVideo(
            track: Int,
            video: VideoSource?,
        ) {}
    }
}
