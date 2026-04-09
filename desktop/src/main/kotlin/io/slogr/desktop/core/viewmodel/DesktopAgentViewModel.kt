package io.slogr.desktop.core.viewmodel

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.profiles.TrafficGrade
import io.slogr.desktop.core.profiles.TrafficType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class ServerResult(
    val serverId: String,
    val label: String,
    val avgRttMs: Float,
    val lossPct: Float,
    val grade: SlaGrade,
    val timestamp: Instant,
    val reachable: Boolean,
)

class DesktopAgentViewModel {

    private val _serverResults = MutableStateFlow<Map<String, ServerResult>>(emptyMap())
    val serverResults: StateFlow<Map<String, ServerResult>> = _serverResults.asStateFlow()

    /** Per-traffic-type grades. Null grade = testing/pending (shown as grey). */
    private val _trafficGrades = MutableStateFlow<Map<String, TrafficGrade>>(emptyMap())
    val trafficGrades: StateFlow<Map<String, TrafficGrade>> = _trafficGrades.asStateFlow()

    private val _overallGrade = MutableStateFlow<SlaGrade?>(null)
    val overallGrade: StateFlow<SlaGrade?> = _overallGrade.asStateFlow()

    private val _lastTestTime = MutableStateFlow<Instant?>(null)
    val lastTestTime: StateFlow<Instant?> = _lastTestTime.asStateFlow()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val _recentHistory = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val recentHistory: StateFlow<List<HistoryEntry>> = _recentHistory.asStateFlow()

    fun setMeasuring(active: Boolean) {
        _isMeasuring.value = active
        if (!active) {
            // Recalculate overall grade from all resolved traffic grades
            val resolved = _trafficGrades.value.values.mapNotNull { it.grade }
            _overallGrade.value = when {
                resolved.isEmpty() -> null
                resolved.any { it == SlaGrade.RED } -> SlaGrade.RED
                resolved.any { it == SlaGrade.YELLOW } -> SlaGrade.YELLOW
                else -> SlaGrade.GREEN
            }
        }
    }

    /** Set all active types to pending (null grade) at start of cycle. */
    fun clearTrafficGrades(activeTypes: List<TrafficType>) {
        val pending = activeTypes.associate { tt ->
            tt.name to TrafficGrade(tt, null, -1f, -1f)
        }
        _trafficGrades.value = pending
    }

    /** Update a single traffic type's grade as its session completes. */
    fun updateTrafficGrade(name: String, grade: TrafficGrade) {
        val updated = _trafficGrades.value.toMutableMap()
        updated[name] = grade
        _trafficGrades.value = updated
        _lastTestTime.value = Clock.System.now()

        // Update overall grade progressively
        val resolved = updated.values.mapNotNull { it.grade }
        _overallGrade.value = when {
            resolved.isEmpty() -> null
            resolved.any { it == SlaGrade.RED } -> SlaGrade.RED
            resolved.any { it == SlaGrade.YELLOW } -> SlaGrade.YELLOW
            else -> SlaGrade.GREEN
        }
    }

    fun updateServerResult(serverId: String, label: String, bundle: MeasurementBundle?, reachable: Boolean) {
        val now = Clock.System.now()
        val sr = if (bundle != null) {
            ServerResult(serverId, label, bundle.twamp.fwdAvgRttMs, bundle.twamp.fwdLossPct, bundle.grade, now, reachable)
        } else {
            ServerResult(serverId, label, -1f, 100f, SlaGrade.RED, now, false)
        }
        val updated = _serverResults.value.toMutableMap()
        updated[serverId] = sr
        _serverResults.value = updated
    }

    fun recordFailure(serverId: String, label: String) {
        updateServerResult(serverId, label, null, false)
    }

    suspend fun refreshHistory(store: LocalHistoryStore) {
        _recentHistory.value = store.getRecentResults(limit = 200)
    }
}
