package io.slogr.desktop.core.history

import io.slogr.agent.contracts.SlaGrade
import kotlinx.datetime.Instant

data class HistoryEntry(
    val id: Long = 0,
    val sessionId: String,
    val reflectorId: String,
    val reflectorHost: String,
    val reflectorRegion: String,
    val profile: String,
    val measuredAt: Instant,
    val avgRttMs: Float,
    val minRttMs: Float,
    val maxRttMs: Float,
    val jitterMs: Float,
    val lossPct: Float,
    val packetsSent: Int,
    val packetsRecv: Int,
    val grade: SlaGrade,
)
