package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Verifies that all three ClickHouse schemas are fully covered by the agent's serialized output.
 *
 * These column names are locked (Rule T6 in CLAUDE.md). Any divergence causes the Ingest Bridge
 * to silently drop the agent's data.
 *
 * Tables verified:
 *   - twamp_raw
 *   - traceroute_raw (top-level fields + per-hop fields via TracerouteHop)
 *   - agent_health
 */
class SchemaContractTest {

    // ── twamp_raw ────────────────────────────────────────────────────────────

    @Test
    fun `MeasurementResult JSON satisfies twamp_raw schema`() {
        val json = SlogrJson.encodeToString(MeasurementResult.serializer(), fakeMeasurementResult())
        val obj  = SlogrJson.parseToJsonElement(json).jsonObject

        val twampRawColumns = listOf(
            "tenant_id", "session_id", "source_agent_id", "dest_agent_id",
            "source_type", "src_cloud", "src_region", "dst_cloud", "dst_region",
            "path_id", "window_ts", "received_at",
            "rtt_min_ms", "rtt_avg_ms", "rtt_max_ms",
            "fwd_min_rtt_ms", "fwd_avg_rtt_ms", "fwd_max_rtt_ms", "fwd_jitter_ms", "fwd_loss_pct",
            "rev_min_rtt_ms", "rev_avg_rtt_ms", "rev_max_rtt_ms", "rev_jitter_ms", "rev_loss_pct",
            "packets_sent", "packets_recv", "schema_version"
        )
        for (col in twampRawColumns) {
            assertTrue(obj.containsKey(col), "twamp_raw: missing column '$col'")
        }
    }

    // ── traceroute_raw ────────────────────────────────────────────────────────

    @Test
    fun `TracerouteResult JSON satisfies traceroute_raw top-level schema`() {
        val json = SlogrJson.encodeToString(TracerouteResult.serializer(), fakeTracerouteResult())
        val obj  = SlogrJson.parseToJsonElement(json).jsonObject

        val tracerouteTopLevelColumns = listOf(
            "tenant_id", "session_id", "path_id", "source_type",
            "direction", "captured_at", "received_at",
            "is_heartbeat", "is_forced_refresh",
            "prev_snapshot_id", "changed_hops", "primary_asn_change",
            "schema_version"
        )
        for (col in tracerouteTopLevelColumns) {
            assertTrue(obj.containsKey(col), "traceroute_raw (top): missing column '$col'")
        }
    }

    @Test
    fun `TracerouteHop JSON satisfies traceroute_raw per-hop schema`() {
        val hop  = TracerouteHop(ttl = 3, ip = "8.8.8.8", asn = 15169, asnName = "GOOGLE", rttMs = 12.5f, lossPct = 0.0f)
        val json = SlogrJson.encodeToString(TracerouteHop.serializer(), hop)
        val obj  = SlogrJson.parseToJsonElement(json).jsonObject

        val hopColumns = listOf("hop_ttl", "hop_ip", "hop_asn", "hop_asn_name", "hop_rtt_ms", "hop_loss_pct")
        for (col in hopColumns) {
            assertTrue(obj.containsKey(col), "traceroute_raw (hop): missing column '$col'")
        }
    }

    // ── agent_health ─────────────────────────────────────────────────────────

    @Test
    fun `HealthSnapshot JSON satisfies agent_health schema`() {
        val json = SlogrJson.encodeToString(HealthSnapshot.serializer(), fakeHealthSnapshot())
        val obj  = SlogrJson.parseToJsonElement(json).jsonObject

        val agentHealthColumns = listOf(
            "tenant_id", "agent_id", "source_type",
            "reported_at", "received_at",
            "last_twamp_success_at", "last_traceroute_success_at",
            "publish_status", "buffer_size_rows", "buffer_oldest_ts",
            "twamp_failure_count", "traceroute_failure_count",
            "publish_failure_count", "worker_restart_count",
            "agent_restart_count", "schema_version"
        )
        for (col in agentHealthColumns) {
            assertTrue(obj.containsKey(col), "agent_health: missing column '$col'")
        }
    }

    // ── Schema version ────────────────────────────────────────────────────────

    @Test
    fun `schema_version is 1 for all three schemas`() {
        val mr  = fakeMeasurementResult()
        val tr  = fakeTracerouteResult()
        val hs  = fakeHealthSnapshot()
        assert(mr.schemaVersion == 1) { "twamp_raw schemaVersion != 1" }
        assert(tr.schemaVersion == 1) { "traceroute_raw schemaVersion != 1" }
        assert(hs.schemaVersion == 1) { "agent_health schemaVersion != 1" }
    }

    @Test
    fun `source_type is agent for all three schemas`() {
        assert(fakeMeasurementResult().sourceType == "agent")
        assert(fakeTracerouteResult().sourceType   == "agent")
        assert(fakeHealthSnapshot().sourceType     == "agent")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val tenantId  = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val agentId   = UUID.fromString("10000000-0000-0000-0000-000000000002")
    private val sessionId = UUID.fromString("10000000-0000-0000-0000-000000000003")
    private val pathId    = UUID.fromString("10000000-0000-0000-0000-000000000004")

    private val voipProfile = SlaProfile(
        name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 2000L, dscp = 46,
        packetSize = 172, rttGreenMs = 150f, rttRedMs = 400f,
        jitterGreenMs = 30f, jitterRedMs = 100f, lossGreenPct = 1f, lossRedPct = 5f
    )

    private fun fakeMeasurementResult() = MeasurementResult(
        tenantId      = tenantId,
        sessionId     = sessionId,
        pathId        = pathId,
        sourceAgentId = agentId,
        destAgentId   = UUID.fromString("00000000-0000-0000-0000-000000000000"),
        srcCloud      = "aws",
        srcRegion     = "us-east-1",
        dstCloud      = "gcp",
        dstRegion     = "us-central1",
        windowTs      = Clock.System.now(),
        profile       = voipProfile,
        fwdMinRttMs   = 10f,
        fwdAvgRttMs   = 12f,
        fwdMaxRttMs   = 15f,
        fwdJitterMs   = 1f,
        fwdLossPct    = 0f,
        packetsSent   = 100,
        packetsRecv   = 100
    )

    private fun fakeTracerouteResult() = TracerouteResult(
        tenantId  = tenantId,
        sessionId = sessionId,
        pathId    = pathId,
        direction = Direction.UPLINK,
        capturedAt = Clock.System.now(),
        hops = listOf(
            TracerouteHop(ttl = 1, ip = "10.0.0.1", rttMs = 1f),
            TracerouteHop(ttl = 2, ip = "8.8.8.8",  asn = 15169, asnName = "GOOGLE", rttMs = 10f)
        )
    )

    private fun fakeHealthSnapshot() = HealthSnapshot(
        agentId                = agentId,
        tenantId               = tenantId,
        reportedAt             = Clock.System.now(),
        publishStatus          = PublishStatus.OK,
        bufferSizeRows         = 0,
        twampFailureCount      = 0,
        tracerouteFailureCount = 0,
        publishFailureCount    = 0,
        workerRestartCount     = 0,
        agentRestartCount      = 0
    )
}
