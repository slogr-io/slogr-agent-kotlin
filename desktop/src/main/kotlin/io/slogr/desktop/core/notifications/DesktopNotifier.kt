package io.slogr.desktop.core.notifications

import io.slogr.agent.contracts.SlaGrade
import org.slf4j.LoggerFactory
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Sends OS-level desktop notifications for grade changes.
 * Uses AWT [SystemTray] balloon notifications (Windows) / user notifications (macOS).
 */
object DesktopNotifier {

    private val log = LoggerFactory.getLogger(DesktopNotifier::class.java)

    private var previousGrade: SlaGrade? = null

    /**
     * Call after each measurement cycle with the new overall grade.
     * Fires a notification only if the grade has changed.
     */
    fun onGradeUpdate(newGrade: SlaGrade?, notificationsEnabled: Boolean) {
        if (!notificationsEnabled) {
            previousGrade = newGrade
            return
        }
        if (newGrade == null || previousGrade == null) {
            previousGrade = newGrade
            return
        }
        if (newGrade == previousGrade) return

        val message = gradeChangeMessage(previousGrade!!, newGrade)
        previousGrade = newGrade
        if (message != null) {
            sendNotification("Slogr", message, messageType(newGrade))
        }
    }

    fun reset() {
        previousGrade = null
    }

    internal fun gradeChangeMessage(from: SlaGrade, to: SlaGrade): String? = when {
        from == SlaGrade.GREEN && to == SlaGrade.YELLOW ->
            "Connection quality degraded \u2014 elevated latency detected"
        from == SlaGrade.GREEN && to == SlaGrade.RED ->
            "Connection quality poor \u2014 significant degradation"
        from == SlaGrade.YELLOW && to == SlaGrade.RED ->
            "Connection quality worsened"
        from == SlaGrade.RED && to == SlaGrade.GREEN ->
            "Connection quality restored"
        from == SlaGrade.YELLOW && to == SlaGrade.GREEN ->
            "Connection quality improved"
        from == SlaGrade.RED && to == SlaGrade.YELLOW ->
            "Connection quality improving"
        else -> null
    }

    private fun messageType(grade: SlaGrade): TrayIcon.MessageType = when (grade) {
        SlaGrade.GREEN -> TrayIcon.MessageType.INFO
        SlaGrade.YELLOW -> TrayIcon.MessageType.WARNING
        SlaGrade.RED -> TrayIcon.MessageType.ERROR
    }

    private fun sendNotification(title: String, message: String, type: TrayIcon.MessageType) {
        try {
            if (!SystemTray.isSupported()) return
            val icons = SystemTray.getSystemTray().trayIcons
            if (icons.isNotEmpty()) {
                icons[0].displayMessage(title, message, type)
            }
        } catch (e: Exception) {
            log.warn("Failed to send notification: {}", e.message)
        }
    }
}
