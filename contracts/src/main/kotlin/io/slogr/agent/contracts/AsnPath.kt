package io.slogr.agent.contracts

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** ASN lookup result, returned by AsnResolver. */
@Serializable
data class AsnInfo(
    val asn: Int,
    val name: String
)

/** Deduplicated ordered ASN path extracted from traceroute hops. */
@Serializable
data class AsnPath(
    val asns: List<Int>,

    @Serializable(with = UuidSerializer::class)
    @SerialName("session_id")
    val sessionId: UUID,

    @SerialName("captured_at")
    val capturedAt: Instant
)
