package io.slogr.agent.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.util.UUID

/** All outputs from a single measurement cycle. */
@Serializable
data class MeasurementBundle(
    val twamp: MeasurementResult,
    val traceroute: TracerouteResult? = null,
    @SerialName("path_change") val pathChange: PathChangeEvent? = null,
    val grade: SlaGrade
)

/** Describes the target of a TWAMP session. */
@Serializable
data class TwampTarget(
    @Serializable(with = InetAddressSerializer::class)
    val ip: InetAddress,

    val port: Int = 862,

    @Serializable(with = UuidSerializer::class)
    @SerialName("agent_id")
    val agentId: UUID? = null,

    @SerialName("device_type") val deviceType: TargetDeviceType = TargetDeviceType.SLOGR_AGENT,
    @SerialName("auth_mode") val authMode: TwampAuthMode = TwampAuthMode.UNAUTHENTICATED,
    @SerialName("key_id") val keyId: String? = null
)
