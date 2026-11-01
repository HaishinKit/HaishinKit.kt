package com.haishinkit.screen

import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextScreenObjectTest {
    private fun text(value: String): TextScreenObject =
        TextScreenObject().apply {
            frame = Rect(0, 0, 300, 300)
            this.value = value
        }

    @Test
    fun missingFontSizeUsesSwiftDefault() {
        val text = TextScreenObject()
        text.elements = mapOf("value" to "Caption")
        assertEquals(32f, text.size)
    }

    @Test
    fun rendersMultipleLinesAndWrapsWithinRequestedWidth() {
        val single = text("Caption")
        val multiple = text("Caption\nCaption")
        val wrapped = text("Caption Caption Caption Caption").apply { frame = Rect(0, 0, 100, 300) }
        listOf(single, multiple, wrapped).forEach { it.layout(MockRenderer()) }
        assertTrue(multiple.bounds.height() > single.bounds.height())
        assertTrue(wrapped.bounds.height() > single.bounds.height())
        assertTrue(wrapped.bounds.width() <= 100)
        assertEquals(Rect(0, 0, 300, 300), multiple.frame)
    }

    @Test
    fun updatesSizeAndColorAfterFirstLayout() {
        val text = text("Caption").apply { color = Color.RED }
        text.layout(MockRenderer())
        val previousHeight = text.bounds.height()
        text.size = 64f
        text.color = Color.BLUE
        text.layout(MockRenderer())
        assertTrue(text.bounds.height() > previousHeight)
        val bitmap = text.bitmap!!
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.any { Color.alpha(it) > 0 && Color.blue(it) > Color.red(it) })
    }

    @Test
    fun emptyTextHasEmptyBounds() {
        val text = text("")
        text.layout(MockRenderer())
        assertTrue(text.bounds.isEmpty)
    }
}
