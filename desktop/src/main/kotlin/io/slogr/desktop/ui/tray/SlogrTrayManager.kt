package io.slogr.desktop.ui.tray

import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.ui.theme.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Raw AWT system tray with plain-ASCII menu items (no emoji — AWT on Windows
 * cannot render Unicode emoji reliably and produces overlapping text).
 *
 * Two menu variants: normal (5 items) and no-servers (3 items).
 */
class SlogrTrayManager(
    private val onOpenWindow: () -> Unit,
    private val onRunTest: () -> Unit,
    private val onQuit: () -> Unit,
) {

    private var trayIcon: TrayIcon? = null
    private var currentGrade: SlaGrade? = null
    private var hasServers = false
    private var lastTestLabel = "No tests yet"
    private var isTesting = false

    fun install() {
        if (!SystemTray.isSupported()) return

        val icon = TrayIconGenerator.createAwtImage(androidx.compose.ui.graphics.Color(0xFF1A1A1A), 16)
        trayIcon = TrayIcon(icon, "Slogr").apply {
            isImageAutoSize = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        SwingUtilities.invokeLater { onOpenWindow() }
                    }
                }
            })
        }
        rebuildMenu()
        try {
            SystemTray.getSystemTray().add(trayIcon)
        } catch (_: Exception) { }
    }

    fun update(grade: SlaGrade?, serversExist: Boolean, measuring: Boolean, lastTestText: String) {
        hasServers = serversExist
        isTesting = measuring
        lastTestLabel = lastTestText
        currentGrade = grade

        // Update icon color
        val color = when {
            !serversExist -> androidx.compose.ui.graphics.Color(0xFF1A1A1A)
            grade == null -> androidx.compose.ui.graphics.Color(0xFF6B6B6B)
            grade == SlaGrade.GREEN -> SlogrGreen
            grade == SlaGrade.YELLOW -> SlogrYellow
            grade == SlaGrade.RED -> SlogrRed
            else -> SlogrGrey
        }
        trayIcon?.image = TrayIconGenerator.createAwtImage(color, 16)

        val tooltip = when {
            !serversExist -> "Slogr - No servers configured"
            grade == null -> "Slogr - Measuring..."
            else -> "Slogr - Connection quality: ${grade.name}"
        }
        trayIcon?.toolTip = tooltip

        rebuildMenu()
    }

    fun remove() {
        trayIcon?.let {
            try { SystemTray.getSystemTray().remove(it) } catch (_: Exception) { }
        }
        trayIcon = null
    }

    private fun rebuildMenu() {
        val popup = PopupMenu()

        if (!hasServers) {
            // No-servers variant: 3 items
            val noSrv = MenuItem("No servers configured")
            noSrv.isEnabled = false
            popup.add(noSrv)

            val hint = MenuItem("Add a server to start")
            hint.isEnabled = false
            popup.add(hint)

            popup.addSeparator()

            val open = MenuItem("Open Slogr")
            open.addActionListener { SwingUtilities.invokeLater { onOpenWindow() } }
            popup.add(open)

            popup.addSeparator()

            val quit = MenuItem("Quit")
            quit.addActionListener { onQuit() }
            popup.add(quit)
        } else {
            // Normal variant: 5 items, plain ASCII only
            val gradeText = when (currentGrade) {
                SlaGrade.GREEN -> "Status: GREEN"
                SlaGrade.YELLOW -> "Status: YELLOW"
                SlaGrade.RED -> "Status: RED"
                null -> "Status: No data"
            }
            val gradeItem = MenuItem(gradeText)
            gradeItem.isEnabled = false
            popup.add(gradeItem)

            val timeItem = MenuItem(lastTestLabel)
            timeItem.isEnabled = false
            popup.add(timeItem)

            popup.addSeparator()

            val runLabel = if (isTesting) "Testing..." else "Run Test Now"
            val runItem = MenuItem(runLabel)
            runItem.isEnabled = !isTesting
            runItem.addActionListener { SwingUtilities.invokeLater { onRunTest() } }
            popup.add(runItem)

            val openItem = MenuItem("Open Slogr")
            openItem.addActionListener { SwingUtilities.invokeLater { onOpenWindow() } }
            popup.add(openItem)

            popup.addSeparator()

            val quitItem = MenuItem("Quit")
            quitItem.addActionListener { onQuit() }
            popup.add(quitItem)
        }

        trayIcon?.popupMenu = popup
    }
}
