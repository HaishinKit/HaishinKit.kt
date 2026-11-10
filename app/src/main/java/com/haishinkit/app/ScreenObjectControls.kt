package com.haishinkit.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class ExampleScreenObjectKind(val label: String) {
    TEXT("Text"),
    IMAGE("Sample image"),
    ANIMATION("Animation"),
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ScreenObjectAddDialog(
    onAdd: (ExampleScreenObjectKind) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add object") },
        text = {
            Column {
                ExampleScreenObjectKind.entries.forEach { kind ->
                    TextButton(onClick = { onAdd(kind) }) { Text(kind.label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Editor chrome belongs to the preview UI, so it is not recorded or streamed. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ScreenObjectSelectionOverlay(
    controller: ScreenObjectDragController,
    onDelete: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val focused = controller.focusedObject
    LaunchedEffect(controller, focused, size) {
        bounds = null
        if (focused != null) {
            while (true) {
                withFrameNanos { bounds = controller.previewBounds(size) }
            }
        }
    }
    val density = LocalDensity.current
    val buttonPixels = with(density) { 48.dp.toPx() }
    val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        bounds?.let { rect ->
            Canvas(Modifier.fillMaxSize()) {
                clipRect {
                    drawRect(Color.Black, rect.topLeft, rect.size, style = Stroke(4.dp.toPx()))
                    drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                }
            }
            TextButton(
                onClick = onDelete,
                modifier =
                    Modifier
                        .offset {
                            IntOffset(
                                (rect.right - buttonPixels / 2).coerceIn(0f, max(0f, size.width - buttonPixels)).roundToInt(),
                                (rect.top - buttonPixels / 2).coerceIn(safeTop, max(safeTop, size.height - safeBottom - buttonPixels)).roundToInt(),
                            )
                        }
                        .semantics { contentDescription = "Delete object" }
                        .size(48.dp)
                        .background(Color(0xFFD32F2F), CircleShape),
            ) {
                Text("×", color = Color.White)
            }
        }
    }
}
