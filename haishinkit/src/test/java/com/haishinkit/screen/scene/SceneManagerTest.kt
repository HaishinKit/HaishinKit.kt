package com.haishinkit.screen.scene

import com.haishinkit.screen.MockScreen
import com.haishinkit.screen.ScreenObjectFactory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SceneManagerTest {
    @Test
    fun testReadAndWrite() {
        val screen = MockScreen(RuntimeEnvironment.getApplication())
        val snapshot =
            Json.decodeFromString<ScreenObjectSnapshot>(
                javaClass.getResource("/screen_object_snapshot.json")!!.readText(),
            )
        val original = ScreenObjectFactory().create(snapshot)
        screen.addChild(original)
        val manager = SceneManager(screen)
        val document = manager.write()

        manager.read(document)
        manager.transition(0)

        val restored = screen.findById(snapshot.id)!!
        assertNotSame(original, restored)
        assertSame(screen, restored.parent)
        assertEquals(snapshot, ScreenObjectSnapshotFactory().create(restored))
        assertEquals(Json.parseToJsonElement(document), Json.parseToJsonElement(manager.write()))
    }
}
