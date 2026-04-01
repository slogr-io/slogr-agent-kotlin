package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MeasurementResult(
    @SerialName("tenant_id")
    @Serializable(with = UuidSerializer::class)
    val tenantId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000"),

    @SerialName("session_id")
    @Serializable(with = UuidSerializer::class)
    val sessionId: UUID,

    @SerialName("path_id")
    @Serializable(with = UuidSerializer::class)
    val pathId: UUID,

    @SerialName("source_agent_id")
    @Serializable(with = UuidSerializer::class)
    val sourceAgentId: UUID,

    @SerialName("dest_agent_id")
    @Serializable(with = UuidSerializer::class)
    val destAgentId: UUID,

    @SerialName("source_type") val sourceType: String = "agent",
    @SerialName("src_cloud") val srcCloud: String,
    @SerialName("src_region") val srcRegion: String,
    @SerialName("dst_cloud") val dstCloud: String,
    @SerialName("dst_region") val dstRegion: String,
    @SerialName("window_ts") val windowTs: Instant,
    @SerialName("received_at") val receivedAt: Instant = Clock.System.now(),

    val profile: SlaProfile,

    @SerialName("fwd_min_rtt_ms") val fwdMinRttMs: Float,
    @SerialName("fwd_avg_rtt_ms") val fwdAvgRttMs: Float,
    @SerialName("fwd_max_rtt_ms") val fwdMaxRttMs: Float,
    @SerialName("fwd_jitter_ms") val fwdJitterMs: Float,
    @SerialName("fwd_loss_pct") val fwdLossPct: Float,

    @SerialName("rev_min_rtt_ms") val revMinRttMs: Float? = null,
    @SerialName("rev_avg_rtt_ms") val revAvgRttMs: Float? = null,
    @SerialName("rev_max_rtt_ms") val revMaxRttMs: Float? = null,
    @SerialName("rev_jitter_ms") val revJitterMs: Float? = null,
    @SerialName("rev_loss_pct") val revLossPct: Float? = null,

    @SerialName("packets_sent") val packetsSent: Int,
    @SerialName("packets_recv") val packetsRecv: Int,

    val packets: List<PacketEntry>? = null,
    val grade: SlaGrade? = null,

    @SerialName("schema_version") val schemaVersion: Int = 1
)
