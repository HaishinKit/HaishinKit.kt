package com.haishinkit.screen.scene

import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import kotlinx.serialization.json.Json
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenObjectSnapshotTest {
    @Test
    fun testToJsonString() {
        val video = VideoScreenObject()
        val text = TextScreenObject()
        text.value = "Hello"

        val container = ScreenObjectContainer()
        container.addChild(video)
        container.addChild(text)

        val factory = ScreenObjectSnapshotFactory()
        factory.create(container)
        val actual = Json.Default.encodeToString(factory.create(container))
        Assert.assertEquals(
            """{"type":"container","frame":{"x":0,"y":0,"width":0,"height":0},"layoutMargin":{"top":0,"left":0,"bottom":0,"right":0},"elements":{},"children":[{"type":"video","frame":{"x":0,"y":0,"width":0,"height":0},"layoutMargin":{"top":0,"left":0,"bottom":0,"right":0},"elements":{},"children":[]},{"type":"text","frame":{"x":0,"y":0,"width":0,"height":0},"layoutMargin":{"top":0,"left":0,"bottom":0,"right":0},"elements":{"value":"Hello"},"children":[]}]}""",
            actual,
        )
    }
}
