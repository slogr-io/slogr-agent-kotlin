package io.slogr.agent.platform.commands

import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles `push_config` commands.
 *
 * Applies configuration updates in-memory immediately. Changes take effect on
 * the next measurement cycle. Persistence to disk is a future enhancement.
 *
 * Supported fields:
 * - `twamp_packet_count`                     — Int (≥ 1)
 * - `traceroute_max_hops`                    — Int (1–64)
 * - `reporting_threshold_ms`                 — Long (≥ 0)
 * - `buffer_flush_interval_s`                — Int (≥ 1)
 * - `traceroute_heartbeat_interval_cycles`   — Int (≥ 1)
 * - `traceroute_forced_refresh_hours`        — Int (≥ 1)
 * - `traceroute_probes_per_hop`              — Int (1–10)
 * - `traceroute_fallback_modes`              — List<String> subset of ["ICMP","TCP","UDP"]
 */
class PushConfigHandler(
    private val agentId:  UUID,
    private val tenantId: UUID,
    val twampPacketCount:                  AtomicInteger   = AtomicInteger(100),
    val tracerouteMaxHops:                 AtomicInteger   = AtomicInteger(30),
    val reportingThresholdMs:              AtomicLong      = AtomicLong(0),
    val bufferFlushIntervalS:              AtomicInteger   = AtomicInteger(10),
    val tracerouteHeartbeatIntervalCycles: AtomicInteger   = AtomicInteger(6),
    val tracerouteForcedRefreshHours:      AtomicInteger   = AtomicInteger(6),
    val tracerouteProbesPerHop:            AtomicInteger   = AtomicInteger(3),
    val tracerouteFallbackModes:           AtomicReference<List<String>> =
        AtomicReference(listOf("ICMP", "TCP", "UDP"))
) : CommandHandler {

    private val log = LoggerFactory.getLogger(PushConfigHandler::class.java)

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        val obj = payload.jsonObject
        val errors = mutableListOf<String>()

        // Apply each field with validation; collect errors before applying
        val updates = mutableListOf<() -> Unit>()

        obj["twamp_packet_count"]?.jsonPrimitive?.int?.let { v ->
            if (v < 1) errors += "twamp_packet_count must be ≥ 1 (got $v)"
            else updates += { twampPacketCount.set(v) }
        }
        obj["traceroute_max_hops"]?.jsonPrimitive?.int?.let { v ->
            if (v < 1 || v > 64) errors += "traceroute_max_hops must be 1–64 (got $v)"
            else updates += { tracerouteMaxHops.set(v) }
        }
        obj["reporting_threshold_ms"]?.jsonPrimitive?.long?.let { v ->
            if (v < 0) errors += "reporting_threshold_ms must be ≥ 0 (got $v)"
            else updates += { reportingThresholdMs.set(v) }
        }
        obj["buffer_flush_interval_s"]?.jsonPrimitive?.int?.let { v ->
            if (v < 1) errors += "buffer_flush_interval_s must be ≥ 1 (got $v)"
            else updates += { bufferFlushIntervalS.set(v) }
        }
        obj["traceroute_heartbeat_interval_cycles"]?.jsonPrimitive?.int?.let { v ->
            if (v < 1) errors += "traceroute_heartbeat_interval_cycles must be ≥ 1 (got $v)"
            else updates += { tracerouteHeartbeatIntervalCycles.set(v) }
        }
        obj["traceroute_forced_refresh_hours"]?.jsonPrimitive?.int?.let { v ->
            if (v < 1) errors += "traceroute_forced_refresh_hours must be ≥ 1 (got $v)"
            else updates += { tracerouteForcedRefreshHours.set(v) }
        }
        obj["traceroute_probes_per_hop"]?.jsonPrimitive?.int?.let { v ->
            if (v < 1 || v > 10) errors += "traceroute_probes_per_hop must be 1–10 (got $v)"
            else updates += { tracerouteProbesPerHop.set(v) }
        }
        obj["traceroute_fallback_modes"]?.jsonArray?.let { arr ->
            val valid = setOf("ICMP", "TCP", "UDP")
            val modes = arr.map { it.jsonPrimitive.content }
            val invalid = modes.filter { it !in valid }
            if (invalid.isNotEmpty()) errors += "traceroute_fallback_modes contains unknown values: $invalid"
            else updates += { tracerouteFallbackModes.set(modes) }
        }

        if (errors.isNotEmpty()) {
            val msg = errors.joinToString("; ")
            log.warn("push_config rejected: $msg")
            return CommandResponse(commandId, agentId, tenantId, "failed", error = msg)
        }

        updates.forEach { it() }
        log.info("push_config applied: packetCount=${twampPacketCount.get()} " +
                 "maxHops=${tracerouteMaxHops.get()} thresholdMs=${reportingThresholdMs.get()} " +
                 "heartbeatCycles=${tracerouteHeartbeatIntervalCycles.get()} " +
                 "forcedRefreshHours=${tracerouteForcedRefreshHours.get()}")
        return CommandResponse(commandId, agentId, tenantId, "acked")
    }
}
