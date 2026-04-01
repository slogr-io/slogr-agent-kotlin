package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class HealthSnapshot(
    @Serializable(with = UuidSerializer::class)
    @SerialName("agent_id")
    val agentId: UUID,

    @Serializable(with = UuidSerializer::class)
    @SerialName("tenant_id")
    val tenantId: UUID,

    @SerialName("source_type") val sourceType: String = "agent",
    @SerialName("reported_at") val reportedAt: Instant,
    @SerialName("received_at") val receivedAt: Instant = Clock.System.now(),
    @SerialName("last_twamp_success_at") val lastTwampSuccessAt: Instant? = null,
    @SerialName("last_traceroute_success_at") val lastTracerouteSuccessAt: Instant? = null,
    @SerialName("publish_status") val publishStatus: PublishStatus,
    @SerialName("buffer_size_rows") val bufferSizeRows: Int,
    @SerialName("buffer_oldest_ts") val bufferOldestTs: Instant? = null,
    @SerialName("twamp_failure_count") val twampFailureCount: Int,
    @SerialName("traceroute_failure_count") val tracerouteFailureCount: Int,
    @SerialName("publish_failure_count") val publishFailureCount: Int,
    @SerialName("worker_restart_count") val workerRestartCount: Int,
    @SerialName("agent_restart_count") val agentRestartCount: Int,
    @SerialName("schema_version") val schemaVersion: Int = 1
)
