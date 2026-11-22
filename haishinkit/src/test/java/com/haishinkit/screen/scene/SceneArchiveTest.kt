package com.haishinkit.screen.scene

import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.ScreenObjectFactory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SceneArchiveTest {
    private fun fixture(): ScreenObjectSnapshot = Json.decodeFromString(javaClass.getResource("/screen_object_snapshot.json")!!.readText())

    @Test fun archiveRoundTripKeepsLayoutAndOpaqueResources() {
        val original = SceneArchive(fixture(), mapOf("movie.bin" to byteArrayOf(0, 1, 2, 3)))
        val encoded = original.write()
        java.io.File("build/scene-interop/android_scene_deflate.haishinscene").apply {
            parentFile.mkdirs()
            writeBytes(encoded)
        }
        val restored = SceneArchive.read(encoded)
        assertEquals(original.snapshot, restored.snapshot)
        assertArrayEquals(original.resources["movie.bin"], restored.resources["movie.bin"])
    }

    @Test fun unsupportedObjectsAreInvisibleAndPreserveTheirOriginalFields() {
        val unknown =
            fixture().copy(
                type = "asset",
                id = "apple-only",
                isVisible = true,
                elements = mapOf("source" to "media/movie.mp4", "looping" to "true"),
            )
        val objectValue = ScreenObjectFactory().create(unknown)
        assertFalse(objectValue.isVisible)
        assertEquals(unknown, ScreenObjectSnapshotFactory().create(objectValue))
    }

    @Test fun readsAppleStoredArchive() {
        val input = javaClass.getResourceAsStream("/apple_scene_store.haishinscene")!!.readBytes()
        val restored = SceneArchive.read(input)
        assertEquals("swift-fixture", restored.snapshot.id)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.resources["shared.bin"])
        assertEquals("apple.future", restored.snapshot.children[0].type)
    }

    @Test fun rejectsTraversalEntries() {
        try {
            SceneArchive(fixture(), mapOf("../escape" to byteArrayOf(1)))
            fail("Traversal accepted")
        } catch (_: IllegalArgumentException) {
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../scene.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        try {
            SceneArchive.read(output.toByteArray())
            fail("Traversal accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun packagedImageCanUseAnAppOwnedFileUri() {
        val snapshot = fixture().children[1].children[1]
        val source = ScreenObjectFactory().create(snapshot) as ImageScreenObject
        val file = java.io.File.createTempFile("scene-image", ".png")
        try {
            file.outputStream().use { source.bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val restored = ScreenObjectFactory().create(snapshot.copy(elements = mapOf("source" to file.toURI().toString()))) as ImageScreenObject
            assertEquals(source.bitmap!!.width, restored.bitmap!!.width)
            assertEquals(file.toURI().toString(), restored.elements["source"])
            val portable = SceneArchive(ScreenObjectSnapshotFactory().create(restored), mapOf(file.name to file.readBytes()))
            assertEquals("media/${file.name}", portable.snapshot.elements["source"])
        } finally {
            file.delete()
        }
    }
}
