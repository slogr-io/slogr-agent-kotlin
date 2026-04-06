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
    fun `surface color matches design spec 121821`() {
        assertEquals(Color(0xFF121821), SlogrSurface)
    }

    @Test
    fun `border color matches design spec 1E2835`() {
        assertEquals(Color(0xFF1E2835), SlogrBorder)
    }

    @Test
    fun `grade colors are defined`() {
        assertEquals(Color(0xFF4CAF50), SlogrGreen)
        assertEquals(Color(0xFFFFC107), SlogrYellow)
        assertEquals(Color(0xFFF44336), SlogrRed)
        assertEquals(Color(0xFF9E9E9E), SlogrGrey)
    }
}
