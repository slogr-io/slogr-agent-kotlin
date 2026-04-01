package io.slogr.agent.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single hop in a traceroute.
 * ip is String? because InetAddress? requires a nullable custom serializer;
 * null means timeout (no response from this hop, displayed as "*").
 */
@Serializable
data class TracerouteHop(
    @SerialName("hop_ttl") val ttl: Int,
    @SerialName("hop_ip") val ip: String? = null,
    @SerialName("hop_asn") val asn: Int? = null,
    @SerialName("hop_asn_name") val asnName: String? = null,
    @SerialName("hop_rtt_ms") val rttMs: Float? = null,
    @SerialName("hop_loss_pct") val lossPct: Float? = null
)
