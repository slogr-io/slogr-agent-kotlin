package io.slogr.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SlogrThemeTest {
    @Test fun `light background`() = assertEquals(Color(0xFFFFFFFF), SlogrBackground)
    @Test fun `sidebar light grey`() = assertEquals(Color(0xFFF5F5F5), SlogrSidebarBg)
    @Test fun `accent green`() = assertEquals(Color(0xFF4CAF50), SlogrGreen)
    @Test fun `text primary dark`() = assertEquals(Color(0xFF212121), TextPrimary)
    @Test fun `grade colors`() { assertEquals(Color(0xFFFFC107), SlogrYellow); assertEquals(Color(0xFFF44336), SlogrRed) }
}
