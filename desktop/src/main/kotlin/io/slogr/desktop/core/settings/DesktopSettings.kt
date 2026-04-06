package io.slogr.desktop.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DesktopSettings(
    @SerialName("active_profile") val activeProfile: String = "internet",
    @SerialName("second_free_profile") val secondFreeProfile: String? = null,
    @SerialName("test_interval_seconds") val testIntervalSeconds: Int = 300,
    @SerialName("traceroute_enabled") val tracerouteEnabled: Boolean = true,
    @SerialName("selected_reflector_ids") val selectedReflectorIds: List<String> = emptyList(),
    @SerialName("auto_start_enabled") val autoStartEnabled: Boolean = true,
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean = true,
    @SerialName("minimize_to_tray_on_close") val minimizeToTrayOnClose: Boolean = true,
) {
    companion object {
        val TEST_INTERVALS = listOf(60, 120, 300, 600, 900, 1800)

        fun intervalLabel(seconds: Int): String = when {
            seconds < 60 -> "${seconds}s"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "${seconds / 60}m ${seconds % 60}s"
        }
    }
}
