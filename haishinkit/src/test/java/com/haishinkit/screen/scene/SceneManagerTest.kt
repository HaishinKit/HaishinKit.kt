package com.haishinkit.screen.scene

import android.graphics.Color
import com.haishinkit.screen.MockScreen
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SceneManagerTest {
    @Test
    fun testReadAndWrite() {
        val screen = MockScreen(RuntimeEnvironment.getApplication())
        val video = VideoScreenObject()
        val text = TextScreenObject()
        text.value = "Hello World!!"
        text.color = Color.RED
        screen.addChild(video)
        screen.addChild(text)

        val data =
            """{"version":1,"scenes":[{"name":"","screen":{"type":"container","frame":{"x":0,"y":0,"width":0,"height":0},"layout_margin":{"top":0,"left":0,"bottom":0,"right":0},"elements":{},"children":[{"type":"video","frame":{"x":0,"y":0,"width":0,"height":0},"layout_margin":{"top":0,"left":0,"bottom":0,"right":0},"elements":{},"children":[]},{"type":"text","frame":{"x":0,"y":0,"width":0,"height":0},"layout_margin":{"top":0,"left":0,"bottom":0,"right":0},"elements":{"value":"Hello"},"children":[]}]}}]}""".trimIndent()
        val manager = SceneManager(screen)

        println("👺" + manager.write())

        manager.read(data)
        manager.transition(0)
    }
}
