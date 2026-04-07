package io.slogr.desktop.core.profiles

import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.engine.sla.SlaEvaluator
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrafficType(
    val name: String,
    val displayName: String,
    val icon: String,
    val free: Boolean,
    val rttGreenMs: Float,
    val rttRedMs: Float,
    val jitterGreenMs: Float,
    val jitterRedMs: Float,
    val lossGreenPct: Float,
    val lossRedPct: Float,
)

/**
 * Per-traffic-type grade result for the dashboard.
 */
data class TrafficGrade(
    val trafficType: TrafficType,
    val grade: SlaGrade,
    val avgRttMs: Float,
    val lossPct: Float,
)

class ProfileManager(
    private val settingsStore: DesktopSettingsStore,
    private val stateManager: DesktopStateManager,
) {

    companion object {
        val ALL_TRAFFIC_TYPES = listOf(
            TrafficType("internet", "General Internet", "\uD83C\uDF10", true, 100f, 200f, 30f, 50f, 1f, 5f),
            TrafficType("gaming", "Gaming", "\uD83C\uDFAE", true, 50f, 100f, 15f, 30f, 0.5f, 2f),
            TrafficType("voip", "VoIP / Video Calls", "\uD83D\uDCDE", true, 150f, 300f, 20f, 40f, 1f, 3f),
            TrafficType("streaming", "Streaming", "\uD83C\uDFAC", true, 200f, 400f, 50f, 80f, 2f, 5f),
            TrafficType("cloud", "Cloud / SaaS", "\u2601\uFE0F", false, 100f, 200f, 25f, 50f, 0.5f, 2f),
            TrafficType("rdp", "Remote Desktop", "\uD83D\uDDA5\uFE0F", false, 80f, 150f, 20f, 40f, 0.5f, 2f),
            TrafficType("iot", "IoT / Telemetry", "\uD83D\uDCE1", false, 500f, 1000f, 100f, 200f, 5f, 10f),
            TrafficType("trading", "Financial Trading", "\uD83D\uDCC8", false, 10f, 30f, 2f, 5f, 0.01f, 0.1f),
        )
    }

    private val _activeProfiles = MutableStateFlow(listOf("gaming", "voip", "streaming"))
    val activeProfiles: StateFlow<List<String>> = _activeProfiles.asStateFlow()

    fun initialize(settings: DesktopSettings) {
        _activeProfiles.value = settings.activeProfiles.take(DesktopSettings.MAX_ACTIVE_PROFILES)
    }

    fun toggleProfile(name: String): String? {
        val current = _activeProfiles.value.toMutableList()
        if (name in current) {
            if (current.size <= 1) return null // must have at least 1
            current.remove(name)
            _activeProfiles.value = current
            settingsStore.update { it.copy(activeProfiles = current) }
            return null
        }
        if (current.size >= DesktopSettings.MAX_ACTIVE_PROFILES) {
            return "Uncheck one first (max ${DesktopSettings.MAX_ACTIVE_PROFILES})"
        }
        if (!isAvailable(name)) return "Upgrade to Pro to unlock this traffic type"
        current.add(name)
        _activeProfiles.value = current
        settingsStore.update { it.copy(activeProfiles = current) }
        return null
    }

    fun isAvailable(name: String): Boolean {
        if (stateManager.state.value == AgentState.CONNECTED) return true
        val tt = ALL_TRAFFIC_TYPES.find { it.name == name } ?: return false
        return tt.free
    }

    fun getActiveTypes(): List<TrafficType> =
        _activeProfiles.value.mapNotNull { name -> ALL_TRAFFIC_TYPES.find { it.name == name } }

    /**
     * Evaluate a single TWAMP result against all active profiles.
     * Returns one TrafficGrade per active profile.
     */
    fun evaluateAll(result: MeasurementResult): List<TrafficGrade> {
        return getActiveTypes().map { tt ->
            val profile = toSlaProfile(tt)
            val grade = SlaEvaluator.evaluate(result, profile)
            TrafficGrade(tt, grade, result.fwdAvgRttMs, result.fwdLossPct)
        }
    }

    fun worstGrade(grades: List<TrafficGrade>): SlaGrade? {
        if (grades.isEmpty()) return null
        return when {
            grades.any { it.grade == SlaGrade.RED } -> SlaGrade.RED
            grades.any { it.grade == SlaGrade.YELLOW } -> SlaGrade.YELLOW
            else -> SlaGrade.GREEN
        }
    }

    private fun toSlaProfile(tt: TrafficType): SlaProfile = SlaProfile(
        name = tt.name,
        nPackets = 10, intervalMs = 50, waitTimeMs = 2000, dscp = 0, packetSize = 64,
        timingMode = TimingMode.FIXED,
        rttGreenMs = tt.rttGreenMs, rttRedMs = tt.rttRedMs,
        jitterGreenMs = tt.jitterGreenMs, jitterRedMs = tt.jitterRedMs,
        lossGreenPct = tt.lossGreenPct, lossRedPct = tt.lossRedPct,
    )
}
