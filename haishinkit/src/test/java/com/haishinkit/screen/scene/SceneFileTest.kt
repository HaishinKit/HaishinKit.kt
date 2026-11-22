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
class SceneFileTest {
    private fun fixture(): ScreenObjectSnapshot = Json.decodeFromString(javaClass.getResource("/screen_object_snapshot.json")!!.readText())

    @Test fun archiveRoundTripKeepsLayoutAndOpaqueResources() {
        val original = SceneFile(fixture(), mapOf("movie.bin" to byteArrayOf(0, 1, 2, 3)))
        val encoded = original.write()
        java.io.File("build/scene-interop/android_scene_deflate.haishinscene").apply {
            parentFile.mkdirs()
            writeBytes(encoded)
        }
        val restored = SceneFile.read(encoded)
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
        val restored = SceneFile.read(input)
        assertEquals("swift-fixture", restored.snapshot.id)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.resources["shared.bin"])
        assertEquals("apple.future", restored.snapshot.children[0].type)
    }

    @Test fun rejectsTraversalEntries() {
        try {
            SceneFile(fixture(), mapOf("../escape" to byteArrayOf(1)))
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
            SceneFile.read(output.toByteArray())
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
            val portable = SceneFile(ScreenObjectSnapshotFactory().create(restored), mapOf(file.name to file.readBytes()))
            assertEquals("media/${file.name}", portable.snapshot.elements["source"])
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsMissingDirectoryAndEntries() {
        val encoded = SceneFile(fixture()).write()
        val central = (0 until encoded.size - 4).first { encoded[it] == 0x50.toByte() && encoded[it + 1] == 0x4b.toByte() && encoded[it + 2] == 1.toByte() && encoded[it + 3] == 2.toByte() }
        try {
            SceneFile.read(encoded.copyOf(central))
            fail("Truncated ZIP accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun rejectsDirectoryPayloadAndOversizedManifest() {
        for ((name, count) in listOf("media/" to 1, "manifest.json" to 65537, "scene.json" to (4 * 1024 * 1024 + 1))) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(count))
                zip.closeEntry()
            }
            try {
                SceneFile.read(output.toByteArray())
                fail("Oversized or nonempty entry accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun rejectsPortableFilenameCollisions() {
        for ((first, second) in listOf("Logo.png" to "logo.png", "é.png" to "e\u0301.png")) {
            try {
                SceneFile(fixture(), mapOf(first to byteArrayOf(1), second to byteArrayOf(2)))
                fail("Colliding names accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun opaqueSnapshotsDoNotAliasMutableInputOrCaptures() {
        val source = fixture().copy(type = "future.widget")
        val originalLeft = source.layoutMargin.left
        val originalChildLeft = source.children[0].layoutMargin.left
        val objectValue = ScreenObjectFactory().create(source)
        source.layoutMargin.left += 5
        source.children[0].layoutMargin.left += 7
        val first = ScreenObjectSnapshotFactory().create(objectValue)
        assertEquals(originalLeft, first.layoutMargin.left)
        assertEquals(originalChildLeft, first.children[0].layoutMargin.left)
        first.layoutMargin.left += 10
        first.children[0].layoutMargin.left += 20
        val second = ScreenObjectSnapshotFactory().create(objectValue)
        assertEquals(originalLeft, second.layoutMargin.left)
        assertEquals(originalChildLeft, second.children[0].layoutMargin.left)
    }

    @Test fun legacyAndPortableGravityDefaultsStayDistinct() {
        val video = fixture().children[0]
        val legacy = ScreenObjectFactory().create(video) as com.haishinkit.screen.VideoScreenObject
        assertEquals(com.haishinkit.graphics.VideoGravity.RESIZE_ASPECT_FILL, legacy.videoGravity)
        val portable = SceneFile.read(SceneFile(video).write())
        val restored = ScreenObjectFactory().create(portable.snapshot) as com.haishinkit.screen.VideoScreenObject
        assertEquals(com.haishinkit.graphics.VideoGravity.RESIZE_ASPECT, restored.videoGravity)
        legacy.videoGravity = com.haishinkit.graphics.VideoGravity.RESIZE_ASPECT
        val recaptured = ScreenObjectSnapshotFactory().create(legacy)
        assertEquals("resizeAspect", recaptured.elements["videoGravity"])
        assertEquals(com.haishinkit.graphics.VideoGravity.RESIZE_ASPECT, (ScreenObjectFactory().create(recaptured) as com.haishinkit.screen.VideoScreenObject).videoGravity)
        val unknown = fixture().copy(type = "future.widget")
        assertEquals(unknown, SceneFile(unknown).snapshot)
    }
}
