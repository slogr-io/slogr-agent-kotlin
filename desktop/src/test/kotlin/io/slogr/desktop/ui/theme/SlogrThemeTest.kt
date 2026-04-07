package io.slogr.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SlogrThemeTest {

    @Test
    fun `background color matches design spec 0B0F14`() {
        assertEquals(Color(0xFF0B0F14), SlogrBackground)
    }

    @Test
    fun `surface color matches design spec`() {
        assertEquals(Color(0xFF131A22), SlogrSurface)
    }

    @Test
    fun `brand green matches logo color 8BC34A`() {
        assertEquals(Color(0xFF8BC34A), SlogrGreen)
    }

    @Test
    fun `grade colors are defined`() {
        assertEquals(Color(0xFF8BC34A), SlogrGreen)
        assertEquals(Color(0xFFFFC107), SlogrYellow)
        assertEquals(Color(0xFFF44336), SlogrRed)
        assertEquals(Color(0xFF9E9E9E), SlogrGrey)
    }

    @Test
    fun `text colors provide proper contrast on dark background`() {
        // Primary text should be near-white (>0xE0 luminance)
        assert(TextPrimary.red > 0.85f) { "Primary text too dark: ${TextPrimary}" }
        // Secondary text should be light grey (>0.65 luminance)
        assert(TextSecondary.red > 0.6f) { "Secondary text too dark: ${TextSecondary}" }
    }
}
