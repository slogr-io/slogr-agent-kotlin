package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TracerouteResult(
    @SerialName("tenant_id")
    @Serializable(with = UuidSerializer::class)
    val tenantId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000"),

    @SerialName("session_id")
    @Serializable(with = UuidSerializer::class)
    val sessionId: UUID,

    @SerialName("path_id")
    @Serializable(with = UuidSerializer::class)
    val pathId: UUID,

    @SerialName("source_type") val sourceType: String = "agent",
    val direction: Direction,
    @SerialName("captured_at") val capturedAt: Instant,
    @SerialName("received_at") val receivedAt: Instant = Clock.System.now(),
    @SerialName("is_heartbeat") val isHeartbeat: Boolean = false,
    @SerialName("is_forced_refresh") val isForcedRefresh: Boolean = false,
    val hops: List<TracerouteHop>,

    @SerialName("prev_snapshot_id")
    @Serializable(with = UuidSerializer::class)
    val prevSnapshotId: UUID? = null,

    @SerialName("changed_hops") val changedHops: List<Int> = emptyList(),
    @SerialName("primary_asn_change") val primaryAsnChange: Int? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1
)
