package io.slogr.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand
val SlogrGreen = Color(0xFF4CAF50)
val SlogrDarkGreen = Color(0xFF2E7D32)

// Backgrounds
val SlogrBackground = Color(0xFFFFFFFF)
val SlogrSurface = Color(0xFFF5F5F5)
val SlogrSidebarBg = Color(0xFFF5F5F5)
val SlogrSidebarSelected = Color(0xFFE8F5E9)
val SlogrCardBg = Color(0xFFFAFAFA)
val SlogrBorder = Color(0xFFE0E0E0)
val FieldBorder = Color(0xFFE0E0E0)

// Grade
val SlogrYellow = Color(0xFFFFC107)
val SlogrRed = Color(0xFFF44336)
val SlogrGrey = Color(0xFF9E9E9E)

// Text
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)
val TextDisabled = Color(0xFFBDBDBD)

private val SlogrLightScheme = lightColorScheme(
    background = SlogrBackground, surface = SlogrSurface, surfaceVariant = SlogrCardBg,
    outline = SlogrBorder, outlineVariant = FieldBorder,
    primary = SlogrGreen, secondary = SlogrYellow, error = SlogrRed,
    onBackground = TextPrimary, onSurface = TextPrimary, onSurfaceVariant = TextSecondary,
    onPrimary = Color.White,
)

private val SlogrTypography = Typography(
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = TextPrimary),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = TextSecondary),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = TextSecondary),
)

@Composable
fun SlogrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SlogrLightScheme, typography = SlogrTypography, content = content)
}
