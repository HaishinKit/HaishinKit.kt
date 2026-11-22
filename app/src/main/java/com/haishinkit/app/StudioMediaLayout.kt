package com.haishinkit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep the preview in the same composition slot when the window is resized. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioMediaLayout(
    modifier: Modifier = Modifier,
    pictureInPicture: Boolean = false,
    preview: @Composable BoxScope.(expanded: Boolean) -> Unit,
    controls: @Composable (expanded: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("studio-media-layout")) {
        // Use the available content area, including multi-window and the navigation rail.
        // Short landscape phone windows retain the compact edge controls.
        val expanded = !pictureInPicture && maxWidth >= 840.dp && maxHeight >= 480.dp
        val panelWidth = if (maxWidth >= 1100.dp) 360.dp else 320.dp
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black).testTag("studio-preview")) {
                preview(expanded)
                if (!pictureInPicture && !expanded) controls(false)
            }
            if (expanded) {
                Box(Modifier.width(panelWidth).fillMaxHeight().testTag("studio-control-panel")) {
                    controls(true)
                }
            }
        }
    }
}

/** Match the composition bounds so scene gestures never target letterboxing. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioPreviewFrame(
    expanded: Boolean,
    aspectRatio: Float,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.then(if (expanded) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize()).testTag("studio-video-frame"),
            content = content,
        )
    }
}
