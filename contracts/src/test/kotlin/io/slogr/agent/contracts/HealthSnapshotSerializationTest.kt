package io.slogr.agent.contracts

import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class HealthSnapshotSerializationTest {

    private fun sampleSnapshot() = HealthSnapshot(
        agentId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
        tenantId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002"),
        reportedAt = Clock.System.now(),
        publishStatus = PublishStatus.OK,
        bufferSizeRows = 0,
        twampFailureCount = 0,
        tracerouteFailureCount = 0,
        publishFailureCount = 0,
        workerRestartCount = 0,
        agentRestartCount = 0
    )

    @Test
    fun `HealthSnapshot round-trips through JSON`() {
        val original = sampleSnapshot()
        val json = SlogrJson.encodeToString(HealthSnapshot.serializer(), original)
        val decoded = SlogrJson.decodeFromString(HealthSnapshot.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `JSON contains all required ClickHouse agent_health column names`() {
        val json = SlogrJson.encodeToString(HealthSnapshot.serializer(), sampleSnapshot())
        val obj = SlogrJson.parseToJsonElement(json).jsonObject

        val required = listOf(
            "tenant_id", "agent_id", "source_type", "reported_at", "received_at",
            "last_twamp_success_at", "last_traceroute_success_at",
            "publish_status", "buffer_size_rows", "buffer_oldest_ts",
            "twamp_failure_count", "traceroute_failure_count",
            "publish_failure_count", "worker_restart_count",
            "agent_restart_count", "schema_version"
        )
        for (key in required) {
            assertTrue(obj.containsKey(key), "Missing ClickHouse column: $key")
        }
    }

    @Test
    fun `sourceType defaults to agent`() {
        assertEquals("agent", sampleSnapshot().sourceType)
    }

    @Test
    fun `schemaVersion defaults to 1`() {
        assertEquals(1, sampleSnapshot().schemaVersion)
    }

    @Test
    fun `optional timestamps default to null`() {
        val snapshot = sampleSnapshot()
        assertNull(snapshot.lastTwampSuccessAt)
        assertNull(snapshot.lastTracerouteSuccessAt)
        assertNull(snapshot.bufferOldestTs)
    }

    @Test
    fun `HealthSnapshot with DEGRADED status round-trips`() {
        val snapshot = sampleSnapshot().copy(
            publishStatus = PublishStatus.DEGRADED,
            bufferSizeRows = 150,
            twampFailureCount = 3,
            publishFailureCount = 12
        )
        val json = SlogrJson.encodeToString(HealthSnapshot.serializer(), snapshot)
        val decoded = SlogrJson.decodeFromString(HealthSnapshot.serializer(), json)
        assertEquals(snapshot, decoded)
        assertEquals(PublishStatus.DEGRADED, decoded.publishStatus)
    }
}
