package io.slogr.agent.contracts

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.UUID

class SerializersTest {

    @Serializable
    private data class WithUuid(@Serializable(with = UuidSerializer::class) val id: UUID)

    @Serializable
    private data class WithInetAddress(@Serializable(with = InetAddressSerializer::class) val ip: InetAddress)

    @Test
    fun `UuidSerializer round-trips`() {
        val original = WithUuid(UUID.randomUUID())
        val json = SlogrJson.encodeToString(WithUuid.serializer(), original)
        val decoded = SlogrJson.decodeFromString(WithUuid.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `UuidSerializer produces canonical UUID string`() {
        val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val json = SlogrJson.encodeToString(WithUuid.serializer(), WithUuid(id))
        assertTrue(json.contains("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `InetAddressSerializer round-trips IPv4`() {
        val original = WithInetAddress(InetAddress.getByName("192.168.1.1"))
        val json = SlogrJson.encodeToString(WithInetAddress.serializer(), original)
        val decoded = SlogrJson.decodeFromString(WithInetAddress.serializer(), json)
        assertEquals(original.ip.hostAddress, decoded.ip.hostAddress)
    }

    @Test
    fun `InetAddressSerializer produces dotted-decimal string`() {
        val ip = InetAddress.getByName("8.8.8.8")
        val json = SlogrJson.encodeToString(WithInetAddress.serializer(), WithInetAddress(ip))
        assertTrue(json.contains("8.8.8.8"))
    }
}
