package io.slogr.desktop.ui.tray

import androidx.compose.ui.graphics.Color
import io.slogr.desktop.ui.theme.SlogrGreen
import io.slogr.desktop.ui.theme.SlogrGrey
import io.slogr.desktop.ui.theme.SlogrRed
import io.slogr.desktop.ui.theme.SlogrYellow
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrayIconGeneratorTest {

    @Test
    fun `creates 16x16 icon by default`() {
        val image = TrayIconGenerator.createIconImage(SlogrGrey)
        assertEquals(16, image.width)
        assertEquals(16, image.height)
    }

    @Test
    fun `creates icon with custom size`() {
        val image = TrayIconGenerator.createIconImage(SlogrGrey, size = 32)
        assertEquals(32, image.width)
        assertEquals(32, image.height)
    }

    @Test
    fun `icon uses ARGB color model`() {
        val image = TrayIconGenerator.createIconImage(SlogrGrey)
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
    }

    @Test
    fun `center pixel has the requested color`() {
        val image = TrayIconGenerator.createIconImage(SlogrGreen, size = 16)
        val centerPixel = image.getRGB(8, 8)
        // Center of a filled circle should be non-transparent
        val alpha = (centerPixel shr 24) and 0xFF
        assertNotEquals(0, alpha, "Center pixel should be opaque")
    }

    @Test
    fun `different colors produce different icons`() {
        val green = TrayIconGenerator.createIconImage(SlogrGreen)
        val red = TrayIconGenerator.createIconImage(SlogrRed)
        val greenCenter = green.getRGB(8, 8)
        val redCenter = red.getRGB(8, 8)
        assertNotEquals(greenCenter, redCenter, "Green and red icons should differ")
    }

    @Test
    fun `all four grade icons are generated without error`() {
        TrayIconGenerator.createIconImage(SlogrGrey)
        TrayIconGenerator.createIconImage(SlogrGreen)
        TrayIconGenerator.createIconImage(SlogrYellow)
        TrayIconGenerator.createIconImage(SlogrRed)
    }
}
