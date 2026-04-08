package io.slogr.desktop.core.profiles

import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.engine.sla.SlaEvaluator
import io.slogr.agent.contracts.MeasurementResult
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
    // SLA thresholds
    val rttGreenMs: Float,
    val rttRedMs: Float,
    val jitterGreenMs: Float,
    val jitterRedMs: Float,
    val lossGreenPct: Float,
    val lossRedPct: Float,
    // Traffic signature — mimics real application traffic
    val nPackets: Int,
    val intervalMs: Long,
    val packetSize: Int,
    val dscp: Int,
)

data class TrafficGrade(
    val trafficType: TrafficType,
    val grade: SlaGrade?,       // null = testing/pending
    val avgRttMs: Float,
    val lossPct: Float,
    val fwdRttMs: Float = 0f,   // uplink (sender → reflector)
    val revRttMs: Float = 0f,   // downlink (reflector → sender)
)

class ProfileManager(
    private val settingsStore: DesktopSettingsStore,
    private val stateManager: DesktopStateManager,
) {

    companion object {
        val ALL_TRAFFIC_TYPES = listOf(
            //                    name          display               icon   rttG   rttR  jitG  jitR  lossG lossR  pkts  intMs  size  dscp
            TrafficType("internet",  "General Internet",   "\uD83C\uDF10", 100f, 200f, 30f, 50f,  1f,   5f,   50,  50L,  1500, 0),    // BE
            TrafficType("gaming",    "Gaming",             "\uD83C\uDFAE", 50f,  100f, 15f, 30f,  0.5f, 2f,   33,  30L,  120,  34),   // AF41
            TrafficType("voip",      "VoIP / Video Calls", "\uD83D\uDCDE", 150f, 300f, 20f, 40f,  1f,   3f,   50,  20L,  200,  46),   // EF
            TrafficType("streaming", "Streaming",          "\uD83C\uDFAC", 200f, 400f, 50f, 80f,  2f,   5f,   20,  50L,  1200, 36),   // AF42
            TrafficType("cloud",     "Cloud / SaaS",       "\u2601\uFE0F", 100f, 200f, 25f, 50f,  0.5f, 2f,   30,  50L,  1500, 32),   // CS4
            TrafficType("rdp",       "Remote Desktop",     "\uD83D\uDDA5\uFE0F", 80f, 150f, 20f, 40f, 0.5f, 2f, 33,  30L,  500,  26), // AF31
            TrafficType("iot",       "IoT / Telemetry",    "\uD83D\uDCE1", 500f, 1000f, 100f, 200f, 5f, 10f, 10, 100L,  100,  0),    // BE
            TrafficType("trading",   "Financial Trading",  "\uD83D\uDCC8", 10f,  30f,  2f,   5f,  0.01f, 0.1f, 50, 10L, 64,   46),   // EF
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
            if (current.size <= 1) return null
            current.remove(name)
            _activeProfiles.value = current
            settingsStore.update { it.copy(activeProfiles = current) }
            return null
        }
        if (current.size >= DesktopSettings.MAX_ACTIVE_PROFILES) {
            return "Uncheck one first (max ${DesktopSettings.MAX_ACTIVE_PROFILES})"
        }
        current.add(name)
        _activeProfiles.value = current
        settingsStore.update { it.copy(activeProfiles = current) }
        return null
    }

    fun isAvailable(name: String): Boolean = ALL_TRAFFIC_TYPES.any { it.name == name }

    fun getActiveTypes(): List<TrafficType> =
        _activeProfiles.value.mapNotNull { name -> ALL_TRAFFIC_TYPES.find { it.name == name } }

    /** Build the SlaProfile (TWAMP session config) for a traffic type — includes real signature. */
    fun toSlaProfile(tt: TrafficType): SlaProfile = SlaProfile(
        name = tt.name,
        nPackets = tt.nPackets,
        intervalMs = tt.intervalMs,
        waitTimeMs = 2000,
        dscp = tt.dscp,
        packetSize = tt.packetSize,
        timingMode = TimingMode.FIXED,
        rttGreenMs = tt.rttGreenMs, rttRedMs = tt.rttRedMs,
        jitterGreenMs = tt.jitterGreenMs, jitterRedMs = tt.jitterRedMs,
        lossGreenPct = tt.lossGreenPct, lossRedPct = tt.lossRedPct,
    )

    /** Evaluate a TWAMP result against a single traffic type. */
    fun evaluate(result: MeasurementResult, tt: TrafficType): TrafficGrade {
        val grade = SlaEvaluator.evaluate(result, toSlaProfile(tt))
        return TrafficGrade(tt, grade, avgRttMs = result.fwdAvgRttMs, result.fwdLossPct,
            fwdRttMs = result.fwdAvgRttMs, revRttMs = result.revAvgRttMs ?: 0f)
    }

    fun worstGrade(grades: List<TrafficGrade>): SlaGrade? {
        val resolved = grades.mapNotNull { it.grade }
        if (resolved.isEmpty()) return null
        return when {
            resolved.any { it == SlaGrade.RED } -> SlaGrade.RED
            resolved.any { it == SlaGrade.YELLOW } -> SlaGrade.YELLOW
            else -> SlaGrade.GREEN
        }
    }
}
