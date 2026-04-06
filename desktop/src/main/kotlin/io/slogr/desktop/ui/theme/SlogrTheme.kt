package io.slogr.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SlogrBackground = Color(0xFF0B0F14)
val SlogrSurface = Color(0xFF121821)
val SlogrBorder = Color(0xFF1E2835)
val SlogrGreen = Color(0xFF4CAF50)
val SlogrYellow = Color(0xFFFFC107)
val SlogrRed = Color(0xFFF44336)
val SlogrGrey = Color(0xFF9E9E9E)

private val SlogrDarkColorScheme = darkColorScheme(
    background = SlogrBackground,
    surface = SlogrSurface,
    outline = SlogrBorder,
    primary = SlogrGreen,
    secondary = SlogrYellow,
    error = SlogrRed,
    onBackground = Color.White,
    onSurface = Color.White,
    onPrimary = Color.White,
)

@Composable
fun SlogrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlogrDarkColorScheme,
        content = content,
    )
}
