package io.slogr.agent.contracts

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.util.UUID

@Serializable
data class SessionConfig(
    @Serializable(with = UuidSerializer::class)
    @SerialName("path_id")
    val pathId: UUID,

    @Serializable(with = InetAddressSerializer::class)
    @SerialName("target_ip")
    val targetIp: InetAddress,

    @SerialName("target_port") val targetPort: Int = 862,
    val profile: SlaProfile,
    @SerialName("interval_seconds") val intervalSeconds: Int = 300,
    @SerialName("traceroute_enabled") val tracerouteEnabled: Boolean = true,
    @SerialName("skip_cycles") val skipCycles: Int = 0,
    @SerialName("tcp_probe_ports") val tcpProbePorts: List<Int> = listOf(443)
)

@Serializable
data class Schedule(
    val sessions: List<SessionConfig>,
    @SerialName("received_at") val receivedAt: Instant,

    @Serializable(with = UuidSerializer::class)
    @SerialName("command_id")
    val commandId: UUID? = null
)
