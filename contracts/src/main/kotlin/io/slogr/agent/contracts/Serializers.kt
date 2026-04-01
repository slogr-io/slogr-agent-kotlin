package io.slogr.agent.contracts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.UUID

/**
 * Canonical Json instance for Slogr serialization.
 * encodeDefaults=true ensures all ClickHouse columns are always present in the output,
 * even when fields have default values (e.g. schemaVersion=1, sourceType="agent").
 */
val SlogrJson: Json = Json { encodeDefaults = true }

object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

object InetAddressSerializer : KSerializer<InetAddress> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("InetAddress", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: InetAddress) = encoder.encodeString(value.hostAddress)
    override fun deserialize(decoder: Decoder): InetAddress = InetAddress.getByName(decoder.decodeString())
}
