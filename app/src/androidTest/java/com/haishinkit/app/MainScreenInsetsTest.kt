package com.haishinkit.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneAvoidsCutoutStatusBarAndGestureArea() {
        show(390.dp, 720.dp)
        val fixture = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.translate(-fixture.left, -fixture.top)
        val navigation = compose.onNodeWithTag("navigation-Camera").fetchSemanticsNode().boundsInRoot.translate(-fixture.left, -fixture.top)
        assertEquals(24f, content.left, 1f)
        assertEquals(32f, content.top, 1f)
        assertTrue(content.bottom <= navigation.top)
        assertTrue(navigation.bottom <= 672f)
    }

    @Test fun tabletRailAndContentAvoidSystemBars() {
        show(840.dp, 600.dp)
        val fixture = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.translate(-fixture.left, -fixture.top)
        val navigation = compose.onNodeWithTag("navigation-Camera").fetchSemanticsNode().boundsInRoot.translate(-fixture.left, -fixture.top)
        assertTrue(content.left >= navigation.right)
        assertEquals(32f, content.top, 1f)
        assertEquals(552f, content.bottom, 1f)
        assertTrue(navigation.top >= 32f)
    }

    @Test fun pictureInPictureUsesWholeWindow() {
        show(320.dp, 180.dp, true)
        val fixture = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.translate(-fixture.left, -fixture.top)
        assertEquals(0f, content.left, 1f)
        assertEquals(0f, content.top, 1f)
        assertEquals(320f, content.width, 1f)
        assertEquals(180f, content.height, 1f)
        compose.onNodeWithTag("navigation-Camera").assertDoesNotExist()
    }

    private fun show(
        width: Dp,
        height: Dp,
        pip: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, height).testTag("fixture")) {
                    MainScreenLayout(pip, false, MainScreenTab.Camera.id, {}, WindowInsets(24.dp, 32.dp, 16.dp, 48.dp)) {
                        Box(Modifier.fillMaxSize().testTag("content"))
                    }
                }
            }
        }
    }
}
