package com.haishinkit.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.screen.EdgeInsets
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.TextScreenObject
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
        val text = node("text", "title", mapOf("value" to "My scene", "size" to "42.0"))
        val original = SceneFile(root(listOf(image, text)), mapOf("logo.png" to pixels.toByteArray()))
        val store = SampleSceneFiles(context)
        val loaded = store.prepare(SceneFile.read(original.write()))
        try {
            val objects = store.create(loaded)
            assertEquals(listOf("image", "title"), objects.getChildren().map { it.id })
            assertEquals("My scene", (objects.getChildren()[1] as TextScreenObject).value)
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
            assertArrayEquals(byteArrayOf(3), SceneFile.read(store.encode(root(emptyList()))).resources["keep.bin"])
        } finally {
            store.close()
        }
    }

    private fun root(children: List<ScreenObjectSnapshot>) = node("screen", "root").copy(size = ScreenObjectSnapshot.Size(1280, 720), children = children)

    private fun node(
        type: String,
        id: String,
        elements: Map<String, String> = emptyMap(),
    ) = ScreenObjectSnapshot(type, id, ScreenObjectSnapshot.Size(120, 80), true, EdgeInsets(20, 30, 0, 0), 0, 0, elements, emptyList())
}
