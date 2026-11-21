package com.haishinkit.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StudioColors =
    darkColorScheme(
        primary = Color(0xFF65DEC9),
        onPrimary = Color(0xFF00382F),
        primaryContainer = Color(0xFF164D45),
        onPrimaryContainer = Color(0xFFA0F2E1),
        secondary = Color(0xFFB7C9DB),
        onSecondary = Color(0xFF233342),
        secondaryContainer = Color(0xFF293E50),
        onSecondaryContainer = Color(0xFFD6E7F8),
        background = Color(0xFF0B121C),
        onBackground = Color(0xFFE6EDF5),
        surface = Color(0xFF101B28),
        onSurface = Color(0xFFE6EDF5),
        surfaceVariant = Color(0xFF203041),
        onSurfaceVariant = Color(0xFFB8C7D6),
        outline = Color(0xFF7E90A2),
        outlineVariant = Color(0xFF3C5063),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

/** A quiet, high-contrast studio surface keeps attention on the video. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColors,
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
            ),
        content = content,
    )
}

/** Independent of the scene brightness: white controls stay legible over live video. */
internal object PreviewColors {
    val container = Color.Black.copy(alpha = 0.78f)
    val control = Color.Black.copy(alpha = 0.5f)
    val content = Color.White
}
