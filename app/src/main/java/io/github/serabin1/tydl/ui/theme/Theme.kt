package io.github.serabin1.tydl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF06100F)
val DeepTide = Color(0xFF0C1A18)
val Tide = Color(0xFF4FE3C1)
val Foam = Color(0xFFEAF7F3)
val Mist = Color(0xFF8FA9A3)
val Alarm = Color(0xFFFF8A6B)
val Edge = Color(0xFF2A403B)

private val TydlColors = darkColorScheme(
    primary = Tide,
    onPrimary = Ink,
    secondary = Tide,
    onSecondary = Ink,
    background = Ink,
    onBackground = Foam,
    surface = DeepTide,
    onSurface = Foam,
    surfaceVariant = DeepTide,
    onSurfaceVariant = Mist,
    outline = Edge,
    error = Alarm,
)

@Composable
fun TydlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TydlColors, content = content)
}
