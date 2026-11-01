package com.haishinkit.screen.scene

import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.ScreenObjectFactory
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenObjectSnapshotTest {
    private fun fixture(): ScreenObjectSnapshot =
        Json.decodeFromString(
            javaClass.getResource("/screen_object_snapshot.json")!!.readText(),
        )

    @Test
    fun sharedWireFormatRoundTrip() {
        val snapshot = fixture()
        assertEquals(snapshot, Json.decodeFromString<ScreenObjectSnapshot>(Json.encodeToString(snapshot)))
        assertEquals("字幕 🎥\nHaishinKit", snapshot.children[1].children[0].elements["value"])
    }

    @Test
    fun restoresHierarchyAndAllCommonProperties() {
        val snapshot = fixture()
        val root = ScreenObjectFactory().create(snapshot) as ScreenObjectContainer
        assertEquals(listOf("camera", "overlay"), root.getChildren().map { it.id })
        val overlay = root.findById("overlay") as ScreenObjectContainer
        assertSame(root, overlay.parent)
        assertEquals(listOf("caption", "logo"), overlay.getChildren().map { it.id })
        assertEquals(1, (root.findById("camera") as VideoScreenObject).track)
        val text = root.findById("caption") as TextScreenObject
        assertEquals(24.5f, text.size)
        assertEquals("#8044AAFF", text.elements["color"])
        assertNotNull((root.findById("logo") as ImageScreenObject).bitmap)
        assertEquals(snapshot, ScreenObjectSnapshotFactory().create(root))
    }

    @Test
    fun snapshotDoesNotChangeWhenSourceMarginsChange() {
        val root = ScreenObjectFactory().create(fixture())
        val snapshot = ScreenObjectSnapshotFactory().create(root)
        root.layoutMargin.set(1, 2, 3, 4)
        assertEquals(11, snapshot.layoutMargin.top)
        assertEquals(22, snapshot.layoutMargin.left)
        val video = root.findById("camera")!!
        video.layoutMargin.top = 99
        assertEquals(11, snapshot.children[0].layoutMargin.top)
    }

    @Test
    fun restoresScreenAsContainer() {
        val snapshot = fixture().copy(type = "screen")
        val root = ScreenObjectFactory().create(snapshot) as ScreenObjectContainer
        assertEquals(2, root.childCounts)
        assertEquals("root", root.id)
    }
}
