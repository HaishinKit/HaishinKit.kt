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
    fun nestedChildrenUseComputedParentPosition() {
        val root = ScreenObjectContainer().apply { frame = Rect(0, 0, 400, 300) }
        val overlay =
            ScreenObjectContainer().apply {
                frame = Rect(0, 0, 200, 100)
                horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_CENTER
                verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_MIDDLE
            }
        val child =
            ImageScreenObject().apply {
                frame = Rect(0, 0, 40, 20)
                layoutMargin.set(5, 10, 0, 0)
            }
        overlay.addChild(child)
        root.addChild(overlay)
        root.layout(MockRenderer())
        assertEquals(Rect(100, 100, 300, 200), overlay.bounds)
        assertEquals(Rect(110, 105, 150, 125), child.bounds)
    }

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
