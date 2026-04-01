package io.slogr.agent.contracts

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AgentCredential(
    @Serializable(with = UuidSerializer::class)
    @SerialName("agent_id")
    val agentId: UUID,

    @Serializable(with = UuidSerializer::class)
    @SerialName("tenant_id")
    val tenantId: UUID,

    @SerialName("display_name") val displayName: String,
    val jwt: String,
    @SerialName("rabbitmq_jwt") val rabbitmqJwt: String,
    @SerialName("rabbitmq_host") val rabbitmqHost: String,
    @SerialName("rabbitmq_port") val rabbitmqPort: Int,
    @SerialName("pubsub_subscription") val pubsubSubscription: String,
    @SerialName("issued_at") val issuedAt: Instant,
    @SerialName("connected_via") val connectedVia: ConnectionMethod,

    /** GCP service account key JSON (base64-encoded). Set by registration response (R1 auth). */
    @SerialName("gcp_service_account_key") val gcpServiceAccountKey: String? = null
)
