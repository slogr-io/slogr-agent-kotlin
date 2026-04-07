package io.slogr.desktop.core.viewmodel

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.profiles.TrafficGrade
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

    private val _trafficGrades = MutableStateFlow<List<TrafficGrade>>(emptyList())
    val trafficGrades: StateFlow<List<TrafficGrade>> = _trafficGrades.asStateFlow()

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
    }

    fun updateResult(
        serverId: String,
        label: String,
        bundle: MeasurementBundle,
        profileManager: ProfileManager,
    ) {
        val now = Clock.System.now()

        // Per-server result
        val sr = ServerResult(serverId, label, bundle.twamp.fwdAvgRttMs, bundle.twamp.fwdLossPct, bundle.grade, now, true)
        val updated = _serverResults.value.toMutableMap()
        updated[serverId] = sr
        _serverResults.value = updated

        // Evaluate against all 3 active profiles
        val grades = profileManager.evaluateAll(bundle.twamp)
        _trafficGrades.value = grades
        _overallGrade.value = profileManager.worstGrade(grades)
        _lastTestTime.value = now
    }

    fun recordFailure(serverId: String, label: String) {
        val now = Clock.System.now()
        val sr = ServerResult(serverId, label, -1f, 100f, SlaGrade.RED, now, false)
        val updated = _serverResults.value.toMutableMap()
        updated[serverId] = sr
        _serverResults.value = updated
        _lastTestTime.value = now
    }

    suspend fun refreshHistory(store: LocalHistoryStore) {
        _recentHistory.value = store.getRecentResults(limit = 200)
    }
}
