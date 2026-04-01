package io.slogr.agent.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PathChangeEvent(
    @Serializable(with = UuidSerializer::class)
    @SerialName("path_id")
    val pathId: UUID,

    val direction: Direction,

    @SerialName("prev_asn_path")
    val prevAsnPath: List<Int>,

    @SerialName("new_asn_path")
    val newAsnPath: List<Int>,

    @SerialName("primary_changed_asn")
    val primaryChangedAsn: Int,

    @SerialName("primary_changed_asn_name")
    val primaryChangedAsnName: String,

    @SerialName("changed_hop_ttl")
    val changedHopTtl: Int,

    @SerialName("hop_delta_ms")
    val hopDeltaMs: Float
)
