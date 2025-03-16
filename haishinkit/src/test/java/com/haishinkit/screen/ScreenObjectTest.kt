package com.haishinkit.screen

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScreenObjectTest {
    @Test
    fun testScreenRect() {
        val renderer = MockRenderer()
        val mockScreen = MockScreen(RuntimeEnvironment.getApplication())
        mockScreen.frame = Rect(0, 0, 1080, 720)
        mockScreen.layout(renderer)
        assertEquals(mockScreen.bounds.width(), 1080)
        assertEquals(mockScreen.bounds.height(), 720)
    }

    @Test
    fun testScreenRectVideo() {
        val renderer = MockRenderer()
        val mockScreen = MockScreen(RuntimeEnvironment.getApplication())
        val video1 = VideoScreenObject()
        val video2 = VideoScreenObject()
        video2.frame = Rect(10, 10, 0, 0)
        mockScreen.frame = Rect(0, 0, 1080, 720)
        mockScreen.addChild(video1)
        mockScreen.addChild(video2)
        mockScreen.layout(renderer)
        assertEquals(1080, mockScreen.bounds.width())
        assertEquals(720, mockScreen.bounds.height())
        assertEquals(1080, video1.bounds.width())
        assertEquals(720, video1.bounds.height())
        assertEquals(1070, video2.bounds.width())
        assertEquals(710, video2.bounds.height())
    }

    @Test
    fun testScreenRectVideoWithContainer() {
        val renderer = MockRenderer()
        val mockScreen = MockScreen(RuntimeEnvironment.getApplication())
        val container = ScreenObjectContainer()
        val video1 = VideoScreenObject()
        val video2 = VideoScreenObject()
        video2.frame = Rect(10, 10, 0, 0)
        mockScreen.frame = Rect(0, 0, 1080, 720)
        container.addChild(video1)
        container.addChild(video2)
        mockScreen.addChild(container)
        mockScreen.layout(renderer)
        assertEquals(1080, mockScreen.bounds.width())
        assertEquals(720, mockScreen.bounds.height())
        assertEquals(1080, video1.bounds.width())
        assertEquals(720, video1.bounds.height())
        assertEquals(1070, video2.bounds.width())
        assertEquals(710, video2.bounds.height())
    }
}
