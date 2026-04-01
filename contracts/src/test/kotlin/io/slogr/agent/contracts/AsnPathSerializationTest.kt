package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AsnPathSerializationTest {

    @Test
    fun `AsnInfo round-trips through JSON`() {
        val original = AsnInfo(asn = 15169, name = "GOOGLE")
        val json = SlogrJson.encodeToString(AsnInfo.serializer(), original)
        val decoded = SlogrJson.decodeFromString(AsnInfo.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `AsnPath round-trips through JSON`() {
        val original = AsnPath(
            asns = listOf(15169, 16509, 7018),
            sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
            capturedAt = Clock.System.now()
        )
        val json = SlogrJson.encodeToString(AsnPath.serializer(), original)
        val decoded = SlogrJson.decodeFromString(AsnPath.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `AsnPath preserves ASN ordering`() {
        val asns = listOf(15169, 16509, 7018, 3356)
        val path = AsnPath(asns = asns, sessionId = UUID.randomUUID(), capturedAt = Clock.System.now())
        val json = SlogrJson.encodeToString(AsnPath.serializer(), path)
        val decoded = SlogrJson.decodeFromString(AsnPath.serializer(), json)
        assertEquals(asns, decoded.asns)
    }

    @Test
    fun `PathChangeEvent round-trips through JSON`() {
        val original = PathChangeEvent(
            pathId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
            direction = Direction.UPLINK,
            prevAsnPath = listOf(15169, 16509),
            newAsnPath = listOf(15169, 3356),
            primaryChangedAsn = 3356,
            primaryChangedAsnName = "ATT-INTERNET4",
            changedHopTtl = 8,
            hopDeltaMs = 12.5f
        )
        val json = SlogrJson.encodeToString(PathChangeEvent.serializer(), original)
        val decoded = SlogrJson.decodeFromString(PathChangeEvent.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `PathChangeEvent JSON has expected keys`() {
        val event = PathChangeEvent(
            pathId = UUID.randomUUID(),
            direction = Direction.DOWNLINK,
            prevAsnPath = listOf(15169),
            newAsnPath = listOf(3356),
            primaryChangedAsn = 3356,
            primaryChangedAsnName = "ATT",
            changedHopTtl = 5,
            hopDeltaMs = 5.0f
        )
        val json = SlogrJson.encodeToString(PathChangeEvent.serializer(), event)
        val obj = SlogrJson.parseToJsonElement(json).jsonObject

        for (key in listOf("path_id", "direction", "prev_asn_path", "new_asn_path",
                            "primary_changed_asn", "primary_changed_asn_name",
                            "changed_hop_ttl", "hop_delta_ms")) {
            assertTrue(obj.containsKey(key), "Missing key: $key")
        }
    }
}
