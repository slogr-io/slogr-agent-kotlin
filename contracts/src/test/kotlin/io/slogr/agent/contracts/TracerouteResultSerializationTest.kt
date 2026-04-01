package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TracerouteResultSerializationTest {

    private fun sampleResult() = TracerouteResult(
        sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
        pathId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
        direction = Direction.UPLINK,
        capturedAt = Clock.System.now(),
        hops = listOf(
            TracerouteHop(ttl = 1, ip = "192.168.1.1", rttMs = 1.2f),
            TracerouteHop(ttl = 2, ip = null, rttMs = null),  // timeout hop
            TracerouteHop(ttl = 3, ip = "8.8.8.8", asn = 15169, asnName = "GOOGLE", rttMs = 15.3f)
        )
    )

    @Test
    fun `TracerouteResult round-trips through JSON`() {
        val original = sampleResult()
        val json = SlogrJson.encodeToString(TracerouteResult.serializer(), original)
        val decoded = SlogrJson.decodeFromString(TracerouteResult.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `JSON contains all required ClickHouse traceroute_raw column names`() {
        val json = SlogrJson.encodeToString(TracerouteResult.serializer(), sampleResult())
        val obj = SlogrJson.parseToJsonElement(json).jsonObject

        val required = listOf(
            "tenant_id", "session_id", "path_id", "source_type", "direction",
            "captured_at", "received_at", "is_heartbeat", "is_forced_refresh",
            "prev_snapshot_id", "changed_hops", "primary_asn_change", "schema_version"
        )
        for (key in required) {
            assertTrue(obj.containsKey(key), "Missing ClickHouse column: $key")
        }
    }

    @Test
    fun `TracerouteHop JSON contains hop_ prefixed column names`() {
        val hop = TracerouteHop(ttl = 3, ip = "8.8.8.8", asn = 15169, asnName = "GOOGLE", rttMs = 15.3f, lossPct = 0.0f)
        val json = SlogrJson.encodeToString(TracerouteHop.serializer(), hop)
        val obj = SlogrJson.parseToJsonElement(json).jsonObject

        val required = listOf("hop_ttl", "hop_ip", "hop_asn", "hop_asn_name", "hop_rtt_ms", "hop_loss_pct")
        for (key in required) {
            assertTrue(obj.containsKey(key), "Missing hop column: $key")
        }
    }

    @Test
    fun `null ip hop round-trips as null`() {
        val hop = TracerouteHop(ttl = 5, ip = null)
        val json = SlogrJson.encodeToString(TracerouteHop.serializer(), hop)
        val decoded = SlogrJson.decodeFromString(TracerouteHop.serializer(), json)
        assertNull(decoded.ip)
    }

    @Test
    fun `isHeartbeat and isForcedRefresh default to false`() {
        val result = sampleResult()
        assertEquals(false, result.isHeartbeat)
        assertEquals(false, result.isForcedRefresh)
    }

    @Test
    fun `changedHops defaults to empty list`() {
        assertEquals(emptyList<Int>(), sampleResult().changedHops)
    }

    @Test
    fun `schemaVersion defaults to 1`() {
        assertEquals(1, sampleResult().schemaVersion)
    }
}
