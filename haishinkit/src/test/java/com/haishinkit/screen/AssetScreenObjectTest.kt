package com.haishinkit.screen

import android.net.Uri
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.screen.scene.ScreenObjectSnapshotFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetScreenObjectTest {
    @Test fun sceneSnapshotKeepsSourceLoopingAndGravityWithoutStartingAReader() {
        val context = RuntimeEnvironment.getApplication()
        val asset =
            AssetScreenObject(context).apply {
                isLooping = true
                videoGravity = VideoGravity.RESIZE_ASPECT_FILL
                startReading(Uri.parse("file:///private/example.mp4"))
            }
        try {
            val snapshot = ScreenObjectSnapshotFactory().create(asset)
            assertEquals("asset", snapshot.type)
            assertEquals("true", snapshot.elements["looping"])
            assertEquals("resizeAspectFill", snapshot.elements["videoGravity"])
            assertEquals("file:///private/example.mp4", snapshot.elements["source"])
            val restored = ScreenObjectFactory(context).create(snapshot) as AssetScreenObject
            try {
                assertEquals(asset.elements, restored.elements)
            } finally {
                restored.close()
            }
        } finally {
            asset.close()
        }
    }

    @Test fun contextFreeFactoryAndCustomAssetCreatorsPreserveUnknownPayloads() {
        val original = AssetScreenObject(RuntimeEnvironment.getApplication()).apply { startReading(Uri.parse("file:///test.mp4")) }
        val snapshot = ScreenObjectSnapshotFactory().create(original)
        val opaque = ScreenObjectFactory().create(snapshot)
        assertFalse(opaque.isVisible)
        assertEquals(snapshot, ScreenObjectSnapshotFactory().create(opaque))
        val unknown = snapshot.copy(elements = mapOf("format" to "future", "source" to "media/a.bin"))
        assertFalse(ScreenObjectFactory(RuntimeEnvironment.getApplication()).create(unknown).isVisible)
        val custom = TextScreenObject()
        val factory = ScreenObjectFactory(RuntimeEnvironment.getApplication()).apply { register("asset") { custom } }
        assertTrue(factory.create(snapshot) === custom)
        original.close()
    }

    @Test fun explicitCancellationRetainsTheSourceAndClosePreventsRestart() {
        val asset = AssetScreenObject(RuntimeEnvironment.getApplication())
        val uri = Uri.parse("file:///test.mp4")
        asset.startReading(uri)
        assertTrue(asset.isReading)
        asset.cancelReading()
        assertFalse(asset.isReading)
        assertEquals(uri, asset.sourceUri)
        asset.startReading(uri)
        asset.close()
        assertFalse(asset.isReading)
        assertThrows(IllegalStateException::class.java) { asset.startReading(uri) }
    }

    @Test fun invalidSourceReportsAnErrorAndStopsWithoutASurface() {
        val asset = AssetScreenObject(RuntimeEnvironment.getApplication())
        val error = CountDownLatch(1)
        asset.onError = { error.countDown() }
        asset.startReading(Uri.parse("file:///missing/video.mp4"))
        asset.sourceForRendering()
        try {
            assertTrue(error.await(5, TimeUnit.SECONDS))
            assertFalse(asset.isReading)
        } finally {
            asset.close()
        }
    }

    @Test fun emptySourceClearsThePreviouslyAssignedUriAndNetworkUrisAreRejected() {
        val asset = AssetScreenObject(RuntimeEnvironment.getApplication()).apply { startReading(Uri.parse("file:///test.mp4")) }
        asset.elements = emptyMap()
        assertEquals(null, asset.sourceUri)
        assertFalse(asset.isReading)
        assertThrows(IllegalArgumentException::class.java) { asset.startReading(Uri.parse("https://example.com/video.mp4")) }
        asset.close()
    }
}
