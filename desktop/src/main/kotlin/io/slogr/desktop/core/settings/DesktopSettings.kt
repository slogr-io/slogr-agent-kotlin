package io.slogr.desktop.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerEntry(
    val id: String,
    val host: String,
    val port: Int = 862,
    val label: String = "",
) {
    val displayLabel: String get() = label.ifBlank { "$host:$port" }
}

@Serializable
data class DesktopSettings(
    @SerialName("active_profiles") val activeProfiles: List<String> = listOf("gaming", "voip", "streaming"),
    @SerialName("test_interval_seconds") val testIntervalSeconds: Int = 300,
    @SerialName("traceroute_enabled") val tracerouteEnabled: Boolean = false,
    val servers: List<ServerEntry> = emptyList(),
    @SerialName("active_server_id") val activeServerId: String? = null,
    @SerialName("auto_start_enabled") val autoStartEnabled: Boolean = true,
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean = true,
) {
    companion object {
        const val MAX_ACTIVE_PROFILES = 3
        val TEST_INTERVALS = listOf(60, 120, 300, 600, 900, 1800)

        fun intervalLabel(seconds: Int): String = when {
            seconds < 60 -> "${seconds}s"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "${seconds / 60}m ${seconds % 60}s"
        }
    }
}
