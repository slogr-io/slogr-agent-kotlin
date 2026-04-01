package io.slogr.agent.platform.commands

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * R2-CFG-01 through R2-CFG-06: push_config command extended fields.
 *
 * R2-CFG-01: Push all 8 fields — all applied, agent ACKs.
 * R2-CFG-02: Push only one field — only that field changes, others retain previous values.
 * R2-CFG-03: Push invalid value (traceroute_max_hops=-1) — rejected, status=failed, no change.
 * R2-CFG-04: Push empty payload {} — agent ACKs, no config change.
 * R2-CFG-05: Push traceroute_fallback_modes with TCP+UDP (no ICMP) — applied correctly.
 * R2-CFG-06: Config is in-memory only (no persistence between restarts — acknowledged gap).
 */
class PushConfigHandlerTest {

    private val agentId   = UUID.randomUUID()
    private val tenantId  = UUID.randomUUID()
    private val commandId = UUID.randomUUID()

    private fun handler() = PushConfigHandler(agentId = agentId, tenantId = tenantId)

    // ── R2-CFG-01: all 8 fields applied ──────────────────────────────────────

    @Test
    fun `R2-CFG-01 push all 8 config fields applies all and returns acked`() = runBlocking {
        val handler = handler()
        val payload = buildJsonObject {
            put("twamp_packet_count",                   200)
            put("traceroute_max_hops",                  20)
            put("reporting_threshold_ms",               5000L)
            put("buffer_flush_interval_s",              30)
            put("traceroute_heartbeat_interval_cycles", 12)
            put("traceroute_forced_refresh_hours",      8)
            put("traceroute_probes_per_hop",            5)
            put("traceroute_fallback_modes",            buildJsonArray {
                add(JsonPrimitive("TCP"))
                add(JsonPrimitive("UDP"))
            })
        }

        val resp = handler.handle(commandId, payload)
        assertEquals("acked", resp.status)

        assertEquals(200,              handler.twampPacketCount.get())
        assertEquals(20,               handler.tracerouteMaxHops.get())
        assertEquals(5000L,            handler.reportingThresholdMs.get())
        assertEquals(30,               handler.bufferFlushIntervalS.get())
        assertEquals(12,               handler.tracerouteHeartbeatIntervalCycles.get())
        assertEquals(8,                handler.tracerouteForcedRefreshHours.get())
        assertEquals(5,                handler.tracerouteProbesPerHop.get())
        assertEquals(listOf("TCP", "UDP"), handler.tracerouteFallbackModes.get())
    }

    // ── R2-CFG-02: partial push — only specified field changes ────────────────

    @Test
    fun `R2-CFG-02 push only traceroute_heartbeat_interval_cycles changes only that field`() = runBlocking {
        val handler = handler()
        val originalMaxHops    = handler.tracerouteMaxHops.get()
        val originalPacketCount = handler.twampPacketCount.get()

        val payload = buildJsonObject {
            put("traceroute_heartbeat_interval_cycles", 12)
        }

        val resp = handler.handle(commandId, payload)
        assertEquals("acked", resp.status)
        assertEquals(12, handler.tracerouteHeartbeatIntervalCycles.get())

        // Other fields unchanged
        assertEquals(originalMaxHops,     handler.tracerouteMaxHops.get())
        assertEquals(originalPacketCount, handler.twampPacketCount.get())
    }

    @Test
    fun `R2-CFG-02 push only traceroute_max_hops changes only that field`() = runBlocking {
        val handler = handler()
        val originalHeartbeat = handler.tracerouteHeartbeatIntervalCycles.get()

        val payload = buildJsonObject { put("traceroute_max_hops", 15) }
        val resp    = handler.handle(commandId, payload)
        assertEquals("acked", resp.status)
        assertEquals(15, handler.tracerouteMaxHops.get())
        assertEquals(originalHeartbeat, handler.tracerouteHeartbeatIntervalCycles.get())
    }

    // ── R2-CFG-03: invalid value → rejected, no config change ─────────────────

    @Test
    fun `R2-CFG-03 traceroute_max_hops=-1 is rejected with status=failed`() = runBlocking {
        val handler = handler()
        val origMaxHops = handler.tracerouteMaxHops.get()

        val payload = buildJsonObject { put("traceroute_max_hops", -1) }
        val resp    = handler.handle(commandId, payload)
        assertEquals("failed", resp.status)
        assertNotNull(resp.error)
        assertEquals(origMaxHops, handler.tracerouteMaxHops.get(), "Config must not change on validation error")
    }

    @Test
    fun `R2-CFG-03 traceroute_max_hops=65 exceeds max and is rejected`() = runBlocking {
        val handler = handler()
        val orig = handler.tracerouteMaxHops.get()

        val payload = buildJsonObject { put("traceroute_max_hops", 65) }
        val resp    = handler.handle(commandId, payload)
        assertEquals("failed", resp.status)
        assertEquals(orig, handler.tracerouteMaxHops.get())
    }

    @Test
    fun `R2-CFG-03 twamp_packet_count=0 is rejected`() = runBlocking {
        val handler = handler()
        val orig = handler.twampPacketCount.get()

        val payload = buildJsonObject { put("twamp_packet_count", 0) }
        val resp    = handler.handle(commandId, payload)
        assertEquals("failed", resp.status)
        assertEquals(orig, handler.twampPacketCount.get())
    }

    @Test
    fun `R2-CFG-03 multiple invalid fields are all reported in error message`() = runBlocking {
        val handler = handler()
        val payload = buildJsonObject {
            put("twamp_packet_count", 0)
            put("traceroute_max_hops", -5)
        }
        val resp = handler.handle(commandId, payload)
        assertEquals("failed", resp.status)
        assertTrue(resp.error?.contains("twamp_packet_count") == true)
        assertTrue(resp.error?.contains("traceroute_max_hops") == true)
    }

    // ── R2-CFG-04: empty payload → ACKed, no change ───────────────────────────

    @Test
    fun `R2-CFG-04 empty payload ACKs with no config change`() = runBlocking {
        val handler = handler()
        val origCount = handler.twampPacketCount.get()
        val origHops  = handler.tracerouteMaxHops.get()

        val resp = handler.handle(commandId, JsonObject(emptyMap()))
        assertEquals("acked", resp.status)
        assertEquals(origCount, handler.twampPacketCount.get())
        assertEquals(origHops,  handler.tracerouteMaxHops.get())
    }

    // ── R2-CFG-05: traceroute_fallback_modes — TCP+UDP only ──────────────────

    @Test
    fun `R2-CFG-05 traceroute_fallback_modes TCP+UDP applied correctly`() = runBlocking {
        val handler = handler()
        val payload = buildJsonObject {
            put("traceroute_fallback_modes", buildJsonArray {
                add(JsonPrimitive("TCP"))
                add(JsonPrimitive("UDP"))
            })
        }

        val resp = handler.handle(commandId, payload)
        assertEquals("acked", resp.status)
        assertEquals(listOf("TCP", "UDP"), handler.tracerouteFallbackModes.get())
    }

    @Test
    fun `R2-CFG-05 unknown mode in traceroute_fallback_modes is rejected`() = runBlocking {
        val handler = handler()
        val orig = handler.tracerouteFallbackModes.get()

        val payload = buildJsonObject {
            put("traceroute_fallback_modes", buildJsonArray {
                add(JsonPrimitive("SCTP"))   // not valid
            })
        }
        val resp = handler.handle(commandId, payload)
        assertEquals("failed", resp.status)
        assertEquals(orig, handler.tracerouteFallbackModes.get())
    }

    // ── R2-CFG-06: in-memory only (no persistence) ───────────────────────────

    @Test
    fun `R2-CFG-06 config is in-memory only — new handler instance starts with defaults`() = runBlocking {
        val h1 = handler()
        h1.handle(commandId, buildJsonObject { put("traceroute_max_hops", 15) })
        assertEquals(15, h1.tracerouteMaxHops.get())

        // Simulate restart — new handler instance loses the pushed config
        val h2 = handler()
        assertEquals(30, h2.tracerouteMaxHops.get(),
            "Default is restored — in-memory only, no persistence (acknowledged gap)")
    }
}
