package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class MeasurementResultSerializationTest {

    private val voipProfile = SlaProfile(
        name = "voip",
        nPackets = 100,
        intervalMs = 20L,
        waitTimeMs = 2000L,
        dscp = 46,
        packetSize = 172,
        rttGreenMs = 150f,
        rttRedMs = 400f,
        jitterGreenMs = 30f,
        jitterRedMs = 100f,
        lossGreenPct = 1f,
        lossRedPct = 5f
    )

    private fun sampleResult() = MeasurementResult(
        sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
        pathId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
        sourceAgentId = UUID.fromString("550e8400-e29b-41d4-a716-446655440003"),
        destAgentId = UUID.fromString("550e8400-e29b-41d4-a716-446655440004"),
        srcCloud = "aws",
        srcRegion = "us-east-1",
        dstCloud = "gcp",
        dstRegion = "us-central1",
        windowTs = Clock.System.now(),
        profile = voipProfile,
        fwdMinRttMs = 10.5f,
        fwdAvgRttMs = 12.3f,
        fwdMaxRttMs = 15.1f,
        fwdJitterMs = 1.2f,
        fwdLossPct = 0.0f,
        packetsSent = 100,
        packetsRecv = 100
    )

    @Test
    fun `MeasurementResult round-trips through JSON`() {
        val original = sampleResult()
        val json = SlogrJson.encodeToString(MeasurementResult.serializer(), original)
        val decoded = SlogrJson.decodeFromString(MeasurementResult.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `JSON contains all required ClickHouse twamp_raw column names`() {
        val json = SlogrJson.encodeToString(MeasurementResult.serializer(), sampleResult())
        val obj = SlogrJson.parseToJsonElement(json).jsonObject

        val required = listOf(
            "tenant_id", "session_id", "source_agent_id", "dest_agent_id", "source_type",
            "src_cloud", "src_region", "dst_cloud", "dst_region",
            "path_id", "window_ts", "received_at",
            "fwd_min_rtt_ms", "fwd_avg_rtt_ms", "fwd_max_rtt_ms",
            "fwd_jitter_ms", "fwd_loss_pct",
            "packets_sent", "packets_recv", "schema_version"
        )
        for (key in required) {
            assertTrue(obj.containsKey(key), "Missing ClickHouse column: $key")
        }
    }

    @Test
    fun `schemaVersion defaults to 1`() {
        assertEquals(1, sampleResult().schemaVersion)
    }

    @Test
    fun `sourceType defaults to agent`() {
        assertEquals("agent", sampleResult().sourceType)
    }

    @Test
    fun `reverse metrics are nullable by default`() {
        val result = sampleResult()
        assertNull(result.revMinRttMs)
        assertNull(result.revAvgRttMs)
        assertNull(result.revMaxRttMs)
        assertNull(result.revJitterMs)
        assertNull(result.revLossPct)
    }

    @Test
    fun `MeasurementResult with per-packet data round-trips`() {
        val now = Clock.System.now()
        val result = sampleResult().copy(
            packets = listOf(
                PacketEntry(seq = 1, txTimestamp = now, rxTimestamp = now,
                    fwdDelayMs = 10.5f, revDelayMs = 9.8f, outOfOrder = false)
            ),
            grade = SlaGrade.GREEN
        )
        val json = SlogrJson.encodeToString(MeasurementResult.serializer(), result)
        val decoded = SlogrJson.decodeFromString(MeasurementResult.serializer(), json)
        assertEquals(result, decoded)
    }

    @Test
    fun `SlaProfile round-trips inside MeasurementResult`() {
        val result = sampleResult()
        val json = SlogrJson.encodeToString(MeasurementResult.serializer(), result)
        val decoded = SlogrJson.decodeFromString(MeasurementResult.serializer(), json)
        assertEquals(result.profile, decoded.profile)
        assertEquals("voip", decoded.profile.name)
    }
}
