package io.slogr.agent.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SlaProfile(
    val name: String,
    @SerialName("n_packets") val nPackets: Int,
    @SerialName("interval_ms") val intervalMs: Long,
    @SerialName("wait_time_ms") val waitTimeMs: Long,
    val dscp: Int,
    @SerialName("packet_size") val packetSize: Int,
    @SerialName("timing_mode") val timingMode: TimingMode = TimingMode.FIXED,
    @SerialName("poisson_lambda") val poissonLambda: Double? = null,
    @SerialName("poisson_max_interval") val poissonMaxInterval: Long? = null,
    @SerialName("rtt_green_ms") val rttGreenMs: Float,
    @SerialName("rtt_red_ms") val rttRedMs: Float,
    @SerialName("jitter_green_ms") val jitterGreenMs: Float,
    @SerialName("jitter_red_ms") val jitterRedMs: Float,
    @SerialName("loss_green_pct") val lossGreenPct: Float,
    @SerialName("loss_red_pct") val lossRedPct: Float
)
