package com.example.fitnotes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF154A88),
    secondary = Color(0xFFA9C7F5),
    secondaryContainer = Color(0xFF263D59),
    onSecondaryContainer = Color(0xFFD7E4FF),
    background = Color(0xFF101318),
    surface = Color(0xFF1B1F25),
    onSurface = Color(0xFFE3E7EE),
    onSurfaceVariant = Color(0xFFB5BDC9),
    outlineVariant = Color(0xFF343B45),
)

@Composable
fun FitNotesTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(12.dp)),
        content = content
    )
}
