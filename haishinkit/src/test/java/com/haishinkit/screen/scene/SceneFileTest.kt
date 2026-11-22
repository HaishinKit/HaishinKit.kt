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

    @Test fun canonicalJapaneseMediaReferencesMatchResources() {
        val resource = "か\u3099.png"
        val image = fixture().children[1].children[1].copy(elements = mapOf("source" to "media/が.png"))
        val file = SceneFile(image, mapOf(resource to byteArrayOf(1, 2)))
        assertEquals("media/$resource", SceneFile.read(file.write()).snapshot.elements["source"])
        val opaque = fixture().copy(type = "future.widget", children = listOf(image))
        assertEquals(opaque, SceneFile.read(SceneFile(opaque, mapOf(resource to byteArrayOf(1))).write()).snapshot)
    }

    @Test fun localhostFileUriCanBePackaged() {
        val image = fixture().children[1].children[1].copy(elements = mapOf("source" to "file://localhost/path/logo.png"))
        assertEquals("media/logo.png", SceneFile(image, mapOf("logo.png" to byteArrayOf(1))).snapshot.elements["source"])
    }

    @Test fun rejectsCaseFoldingAliases() {
        for ((first, second) in listOf("Messages.png" to "Meſſages.png", "ß.png" to "ẞ.png")) {
            try {
                SceneFile(fixture(), mapOf(first to byteArrayOf(1), second to byteArrayOf(2)))
                fail("Aliases accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun rejectsCorruptCentralLocalOffsetAndPackedSize() {
        val original = SceneFile(fixture()).write()
        val central = (0 until original.size - 4).first { original[it] == 0x50.toByte() && original[it + 1] == 0x4b.toByte() && original[it + 2] == 1.toByte() && original[it + 3] == 2.toByte() }
        for (field in listOf(42, 20)) {
            val corrupted = original.copyOf()
            corrupted[central + field] = (corrupted[central + field].toInt() xor 1).toByte()
            try {
                SceneFile.read(corrupted)
                fail("Corrupt central entry accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun readsLargeStoredMediaWithinDeclaredLimit() {
        val media = ByteArray(65 * 1024 * 1024) { 3 }
        val output = ByteArrayOutputStream(media.size + 65536)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("scene.json"))
            zip.write(Json.encodeToString(fixture()).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(
                ZipEntry("media/video.bin").apply {
                    method = ZipEntry.STORED
                    size = media.size.toLong()
                    compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(media) }.value
                },
            )
            zip.write(media)
            zip.closeEntry()
        }
        val restored = SceneFile.read(output.toByteArray())
        assertArrayEquals(media, restored.resources["video.bin"])
    }

    @Test fun normalizedFileUriUsesTheActualResourceName() {
        val image = fixture().children[1].children[1].copy(elements = mapOf("source" to "file://localhost/path/が.png"))
        val name = "か\u3099.png"
        assertEquals("media/$name", SceneFile(image, mapOf(name to byteArrayOf(1))).snapshot.elements["source"])
    }

    @Test fun rejectsCorruptedDirectoryCrc() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("media/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("scene.json"))
            zip.write(Json.encodeToString(fixture()).toByteArray())
            zip.closeEntry()
        }
        val bytes = output.toByteArray()
        assertEquals(fixture().id, SceneFile.read(bytes).snapshot.id)
        val central = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 16] = 1
        try {
            SceneFile.read(bytes)
            fail("Corrupted directory CRC accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun rejectsTheSharedConservativeCaseKeyAliases() {
        try {
            SceneFile(fixture(), mapOf("I.png" to byteArrayOf(1), "ı.png" to byteArrayOf(2)))
            fail("Portable aliases accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun fixture(): ScreenObjectSnapshot = Json.decodeFromString(javaClass.getResource("/screen_object_snapshot.json")!!.readText())
}
