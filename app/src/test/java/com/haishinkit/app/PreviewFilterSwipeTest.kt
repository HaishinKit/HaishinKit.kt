package com.haishinkit.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFilterSwipeTest {
    @Test fun horizontalBackgroundSwipesChooseNextAndPreviousEffects() {
        assertEquals(1, previewFilterStep(Offset(200f, 100f), Offset(80f, 110f), 48f, false, false))
        assertEquals(-1, previewFilterStep(Offset(80f, 100f), Offset(200f, 90f), 48f, false, false))
    }

    @Test fun objectMovementAndPinchingDoNotChangeEffects() {
        assertEquals(0, previewFilterStep(Offset.Zero, Offset(120f, 0f), 48f, true, false))
        assertEquals(0, previewFilterStep(Offset.Zero, Offset(120f, 0f), 48f, false, true))
    }

    @Test fun tapsSmallMovementAndVerticalDragsDoNotChangeEffects() {
        assertEquals(0, previewFilterStep(Offset.Zero, Offset.Zero, 48f, false, false))
        assertEquals(0, previewFilterStep(Offset.Zero, Offset(24f, 0f), 48f, false, false))
        assertEquals(0, previewFilterStep(Offset.Zero, Offset(60f, 120f), 48f, false, false))
    }
}
