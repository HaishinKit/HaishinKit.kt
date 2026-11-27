package com.haishinkit.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.screen.EdgeInsets
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import com.haishinkit.screen.scene.SceneFile
import com.haishinkit.screen.scene.ScreenObjectSnapshot
import com.haishinkit.screen.scene.ScreenObjectSnapshotFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class SampleSceneFilesTest {
    @Test fun portableFileRestoresTextImageAndLayoutAndCanBeSavedAgain() {
        val context = RuntimeEnvironment.getApplication()
        val pixels = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            compress(Bitmap.CompressFormat.PNG, 100, pixels)
            recycle()
        }
        val image = node("image", "image", mapOf("source" to "media/logo.png"))
        val text = node("text", "title", mapOf("value" to "My scene", "fontSize" to "42.0"))
        val original = SceneFile(root(listOf(image, text)), mapOf("logo.png" to pixels.toByteArray()))
        val store = SampleSceneFiles(context)
        val loaded = store.prepare(SceneFile.read(original.write()))
        try {
            val objects = store.create(loaded)
            assertEquals(listOf("image", "title"), objects.getChildren().map { it.id })
            assertEquals("My scene", (objects.getChildren()[1] as TextScreenObject).value)
            assertEquals(42f, (objects.getChildren()[1] as TextScreenObject).size)
            assertEquals(8, (objects.getChildren()[0] as ImageScreenObject).bitmap!!.width)
            store.install(loaded)
            objects.frame = Rect(0, 0, 1280, 720)
            val resaved = SceneFile.read(store.encode(ScreenObjectSnapshotFactory().create(objects)))
            assertEquals("media/logo.png", resaved.snapshot.children[0].elements["source"])
            assertArrayEquals(pixels.toByteArray(), resaved.resources.getValue("logo.png"))
            assertEquals(text.layoutMargin, resaved.snapshot.children[1].layoutMargin)
        } finally {
            store.close()
            loaded.directory.deleteRecursively()
        }
    }

    @Test fun unsupportedObjectsAndResourcesSurviveAnEditRoundtrip() {
        val opaque = node("future", "future", mapOf("custom" to "retained"))
        val file = SceneFile(root(listOf(opaque)), mapOf("future.bin" to byteArrayOf(1, 2)))
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(file)
        try {
            val objects = store.create(loaded).apply { frame = Rect(0, 0, 1280, 720) }
            assertFalse(objects.getChildren().single().isVisible)
            store.install(loaded)
            val saved = SceneFile.read(store.encode(ScreenObjectSnapshotFactory().create(objects)))
            assertEquals(opaque, saved.snapshot.children.single())
            assertArrayEquals(byteArrayOf(1, 2), saved.resources["future.bin"])
        } finally {
            store.close()
        }
    }

    @Test fun animationCanBeRestoredAndSavedWithoutAnAndroidResourceId() {
        val json = """{"v":"5.7.4","fr":30,"ip":0,"op":60,"w":100,"h":100,"nm":"Test","ddd":0,"assets":[],"layers":[]}""".toByteArray()
        val asset = node("asset", "animation", mapOf("format" to "lottie", "source" to "media/animation.json"))
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(SceneFile(root(listOf(asset)), mapOf("animation.json" to json)))
        val objects = store.create(loaded).apply { frame = Rect(0, 0, 1280, 720) }
        val animation = objects.getChildren().single() as LottieScreen
        try {
            store.install(loaded)
            val saved = SceneFile.read(store.encode(ScreenObjectSnapshotFactory().create(objects)))
            val restored = saved.snapshot.children.single()
            assertEquals("asset", restored.type)
            assertEquals("lottie", restored.elements["format"])
            assertArrayEquals(json, saved.resources.getValue(restored.elements.getValue("source").removePrefix("media/")))
        } finally {
            animation.cancelAnimation()
            store.close()
        }
    }

    @Test fun invalidImportDoesNotReplaceInstalledResources() {
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val good = store.prepare(SceneFile(root(emptyList()), mapOf("keep.bin" to byteArrayOf(3))))
        store.install(good)
        try {
            assertThrows(IllegalArgumentException::class.java) { store.prepare(SceneFile(root(listOf(node("text", "same"), node("text", "same"))))) }
            assertThrows(IllegalArgumentException::class.java) { store.prepare(SceneFile(root(emptyList()).copy(size = ScreenObjectSnapshot.Size(0, 0)))) }
            assertArrayEquals(byteArrayOf(3), SceneFile.read(store.encode(root(listOf(node("future", "opaque"))))).resources["keep.bin"])
        } finally {
            store.close()
        }
    }

    @Test fun draggedPositionSurvivesPortableSaveWithoutMutatingTheLiveObject() {
        val root = ScreenObjectContainer().apply { frame = Rect(0, 0, 1000, 1000) }
        val text =
            TextScreenObject().apply {
                value = "Moved"
                frame = Rect(0, 0, 100, 60)
                bounds.set(10, 20, 110, 80)
            }
        root.addChild(text)
        val editor = ScreenObjectDragController(root, listOf(text))
        editor.begin(Offset(30f, 40f), IntSize(1000, 1000))
        editor.move(Offset(130f, 140f), IntSize(1000, 1000))
        val before = Rect(text.frame)
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(SceneFile.read(store.encode(store.capture(root))))
        try {
            val restored = store.create(loaded).getChildren().single()
            assertEquals(110, restored.layoutMargin.left)
            assertEquals(120, restored.layoutMargin.top)
            assertEquals(before.width(), restored.frame.width())
            assertEquals(before, text.frame)
        } finally {
            loaded.directory.deleteRecursively()
            store.close()
        }
    }

    @Test fun deletedPackagedImageIsNotIncludedInExport() {
        val pixels = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            compress(Bitmap.CompressFormat.PNG, 100, pixels)
            recycle()
        }
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(SceneFile(root(listOf(node("image", "logo", mapOf("source" to "media/private.png")))), mapOf("private.png" to pixels.toByteArray())))
        try {
            val objects = store.create(loaded)
            store.install(loaded)
            objects.removeChild(objects.getChildren().single())
            assertEquals(emptySet<String>(), SceneFile.read(store.encode(store.capture(objects))).resources.keys)
        } finally {
            store.close()
        }
    }

    @Test fun oversizedAnimationAndAggregateBitmapLayersAreRejectedBeforeInstallation() {
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val animation = node("asset", "animation", mapOf("format" to "lottie", "source" to "media/a.json")).copy(size = ScreenObjectSnapshot.Size(8192, 8192))
        assertThrows(IllegalArgumentException::class.java) { store.prepare(SceneFile(root(listOf(animation)), mapOf("a.json" to byteArrayOf()))) }
        val text = node("text", "large", mapOf("value" to "Invalid", "fontSize" to "NaN"))
        assertThrows(IllegalArgumentException::class.java) { store.prepare(SceneFile(root(listOf(text)))) }
        store.close()
    }

    @Test fun compressedOversizedArchiveIsRejectedOnRead() {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "oversized.scene")
        file.writeBytes(SceneFile(root(emptyList()), mapOf("large.bin" to ByteArray(33 * 1024 * 1024))).write())
        try {
            assertThrows(IllegalArgumentException::class.java) { SampleSceneFiles(context).read(Uri.fromFile(file)) }
        } finally {
            file.delete()
        }
    }

    @Test fun defaultCameraWrapperRemainsBackgroundAfterRestore() {
        val video = VideoScreenObject().apply { track = 0 }
        val background = ScreenObjectContainer().apply { addChild(video) }
        val mixedGroup =
            ScreenObjectContainer().apply {
                addChild(VideoScreenObject())
                addChild(TextScreenObject().apply { value = "Group label" })
            }
        val root =
            ScreenObjectContainer().apply {
                frame = Rect(0, 0, 1280, 720)
                addChild(background)
                addChild(mixedGroup)
            }
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(SceneFile.read(store.encode(store.capture(root))))
        try {
            val restored = store.create(loaded)
            val editable = store.editableObjects(restored)
            assertEquals(listOf(mixedGroup.id), editable.map { it.id })
            restored.getChildren().first().bounds.set(0, 0, 1280, 720)
            val controller = ScreenObjectDragController(restored, editable)
            assertFalse(controller.begin(Offset(1000f, 600f), IntSize(1280, 720)))
        } finally {
            loaded.directory.deleteRecursively()
            store.close()
        }
    }

    @Test fun opaqueImageDescendantsRemainPortableAndUnchanged() {
        val child = node("image", "hidden-image", mapOf("source" to "media/opaque.bin"))
        val opaque = node("future", "future").copy(children = listOf(child))
        val file = SceneFile(root(listOf(opaque)), mapOf("opaque.bin" to byteArrayOf(1, 2, 3)))
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(SceneFile.read(file.write()))
        try {
            val restored = store.create(loaded)
            store.install(loaded)
            val saved = SceneFile.read(store.encode(store.capture(restored)))
            assertEquals(opaque, saved.snapshot.children.single())
            assertArrayEquals(byteArrayOf(1, 2, 3), saved.resources["opaque.bin"])
        } finally {
            store.close()
        }
    }

    @Test fun multipleShortAutoSizedLabelsCanBeSavedAndOpened() {
        val root = ScreenObjectContainer().apply { frame = Rect(0, 0, 1280, 720) }
        repeat(20) { index ->
            root.addChild(
                TextScreenObject().apply {
                    value = "Label $index"
                    layoutMargin.set(20, 20 + index * 10, 0, 0)
                },
            )
        }
        val store = SampleSceneFiles(RuntimeEnvironment.getApplication())
        val loaded = store.prepare(SceneFile.read(store.encode(store.capture(root))))
        try {
            assertEquals(20, store.create(loaded).getChildren().size)
        } finally {
            loaded.directory.deleteRecursively()
            store.close()
        }
    }

    @Test fun exportRejectsScenesThatTheSampleCannotReopen() {
        val snapshot = root(List(256) { node("video", "video-$it") })
        assertThrows(IllegalArgumentException::class.java) { SampleSceneFiles(RuntimeEnvironment.getApplication()).encode(snapshot) }
    }

    private fun root(children: List<ScreenObjectSnapshot>) = node("screen", "root").copy(size = ScreenObjectSnapshot.Size(1280, 720), children = children)

    private fun node(
        type: String,
        id: String,
        elements: Map<String, String> = emptyMap(),
    ) = ScreenObjectSnapshot(type, id, ScreenObjectSnapshot.Size(120, 80), true, EdgeInsets(20, 30, 0, 0), 0, 0, elements, emptyList())
}
