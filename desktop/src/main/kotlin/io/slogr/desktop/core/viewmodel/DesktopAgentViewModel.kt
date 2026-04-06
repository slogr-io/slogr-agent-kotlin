package io.slogr.desktop.core.viewmodel

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.history.LocalHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Per-reflector measurement result for UI display.
 */
data class ReflectorResult(
    val reflectorId: String,
    val regionName: String,
    val avgRttMs: Float,
    val lossPct: Float,
    val jitterMs: Float,
    val grade: SlaGrade,
    val timestamp: Instant,
)

/**
 * Holds all measurement state and emits UI-ready StateFlows.
 */
class DesktopAgentViewModel {

    private val _results = MutableStateFlow<Map<String, ReflectorResult>>(emptyMap())
    val results: StateFlow<Map<String, ReflectorResult>> = _results.asStateFlow()

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

    fun updateResult(reflectorId: String, regionName: String, bundle: MeasurementBundle) {
        val result = ReflectorResult(
            reflectorId = reflectorId,
            regionName = regionName,
            avgRttMs = bundle.twamp.fwdAvgRttMs,
            lossPct = bundle.twamp.fwdLossPct,
            jitterMs = bundle.twamp.fwdJitterMs,
            grade = bundle.grade,
            timestamp = Clock.System.now(),
        )

        val updated = _results.value.toMutableMap()
        updated[reflectorId] = result
        _results.value = updated

        _lastTestTime.value = result.timestamp
        _overallGrade.value = worstGrade(updated.values)
    }

    fun recordFailure(reflectorId: String, regionName: String) {
        val result = ReflectorResult(
            reflectorId = reflectorId,
            regionName = regionName,
            avgRttMs = -1f,
            lossPct = 100f,
            jitterMs = -1f,
            grade = SlaGrade.RED,
            timestamp = Clock.System.now(),
        )

        val updated = _results.value.toMutableMap()
        updated[reflectorId] = result
        _results.value = updated

        _lastTestTime.value = result.timestamp
        _overallGrade.value = worstGrade(updated.values)
    }

    suspend fun refreshHistory(store: LocalHistoryStore) {
        _recentHistory.value = store.getRecentResults(limit = 200)
    }

    private fun worstGrade(results: Collection<ReflectorResult>): SlaGrade? {
        if (results.isEmpty()) return null
        return when {
            results.any { it.grade == SlaGrade.RED } -> SlaGrade.RED
            results.any { it.grade == SlaGrade.YELLOW } -> SlaGrade.YELLOW
            else -> SlaGrade.GREEN
        }
    }
}
