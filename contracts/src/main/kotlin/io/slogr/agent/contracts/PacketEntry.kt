package io.slogr.agent.contracts

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PacketEntry(
    val seq: Int,
    @SerialName("tx_timestamp") val txTimestamp: Instant,
    @SerialName("rx_timestamp") val rxTimestamp: Instant? = null,
    @SerialName("reflector_proc_time_ns") val reflectorProcTimeNs: Long? = null,
    @SerialName("fwd_delay_ms") val fwdDelayMs: Float? = null,
    @SerialName("rev_delay_ms") val revDelayMs: Float? = null,
    @SerialName("fwd_jitter_ms") val fwdJitterMs: Float? = null,
    @SerialName("rev_jitter_ms") val revJitterMs: Float? = null,
    @SerialName("tx_ttl") val txTtl: Int? = null,
    @SerialName("rx_ttl") val rxTtl: Int? = null,
    @SerialName("out_of_order") val outOfOrder: Boolean = false
)
