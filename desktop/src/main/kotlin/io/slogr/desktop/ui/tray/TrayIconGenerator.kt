package io.slogr.desktop.ui.tray

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.slogr.desktop.ui.theme.SlogrGreen
import io.slogr.desktop.ui.theme.SlogrGrey
import io.slogr.desktop.ui.theme.SlogrRed
import io.slogr.desktop.ui.theme.SlogrYellow
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object TrayIconGenerator {

    private const val ICON_SIZE = 16

    fun greyIcon(): Painter = createIcon(SlogrGrey)
    fun greenIcon(): Painter = createIcon(SlogrGreen)
    fun yellowIcon(): Painter = createIcon(SlogrYellow)
    fun redIcon(): Painter = createIcon(SlogrRed)
    fun staleIcon(): Painter = createIcon(Color(0xFF6B6B6B)) // grey-tint
    fun blackIcon(): Painter = createIcon(Color(0xFF1A1A1A)) // near-black

    fun createIcon(color: Color): Painter {
        val image = createIconImage(color)
        return BitmapPainter(image.toComposeImageBitmap())
    }

    fun createAwtImage(color: Color, size: Int = ICON_SIZE): BufferedImage = createIconImage(color, size)

    internal fun createIconImage(color: Color, size: Int = ICON_SIZE): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
            (color.alpha * 255).toInt(),
        )
        g.fillOval(1, 1, size - 2, size - 2)
        g.dispose()
        return image
    }
}
