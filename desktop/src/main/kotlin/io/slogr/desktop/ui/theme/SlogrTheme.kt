package io.slogr.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand colors
val SlogrGreen = Color(0xFF8BC34A)    // matches the "r" in the logo
val SlogrBackground = Color(0xFF0B0F14)
val SlogrSurface = Color(0xFF131A22)
val SlogrSidebarBg = Color(0xFF161E28)
val SlogrBorder = Color(0xFF2A3441)
val SlogrCardBg = Color(0xFF1A2230)

// Grade colors
val SlogrYellow = Color(0xFFFFC107)
val SlogrRed = Color(0xFFF44336)
val SlogrGrey = Color(0xFF9E9E9E)

// Text colors
val TextPrimary = Color(0xFFE8ECF0)     // near-white for labels
val TextSecondary = Color(0xFFAAB4BF)   // light grey for hints
val TextDisabled = Color(0xFF5A6570)    // medium grey for locked items
val TextMuted = Color(0xFF7A8590)       // secondary data

// Form colors
val FieldBg = Color(0xFF1A2230)
val FieldBorder = Color(0xFF333D4A)
val FieldBorderFocused = SlogrGreen

private val SlogrDarkColorScheme = darkColorScheme(
    background = SlogrBackground,
    surface = SlogrSurface,
    surfaceVariant = SlogrCardBg,
    outline = SlogrBorder,
    outlineVariant = FieldBorder,
    primary = SlogrGreen,
    secondary = SlogrYellow,
    error = SlogrRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    onPrimary = Color(0xFF1A1A1A),
    inverseSurface = TextMuted,
)

private val SlogrTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SlogrGreen),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = TextPrimary),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = TextSecondary),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = TextMuted),
)

@Composable
fun SlogrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlogrDarkColorScheme,
        typography = SlogrTypography,
        content = content,
    )
}
