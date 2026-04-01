package io.slogr.agent.platform.commands

import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles `push_config` commands.
 *
 * Applies configuration updates in-memory immediately. Changes take effect on
 * the next measurement cycle. Persistence to disk is a future enhancement.
 *
 * Supported fields:
 * - `twamp_packet_count`      — Int
 * - `traceroute_max_hops`     — Int
 * - `reporting_threshold_ms`  — Long
 * - `buffer_flush_interval_s` — Int
 */
class PushConfigHandler(
    private val agentId:  UUID,
    private val tenantId: UUID,
    val twampPacketCount:      AtomicInteger = AtomicInteger(100),
    val tracerouteMaxHops:     AtomicInteger = AtomicInteger(30),
    val reportingThresholdMs:  AtomicLong    = AtomicLong(0),
    val bufferFlushIntervalS:  AtomicInteger = AtomicInteger(10)
) : CommandHandler {

    private val log = LoggerFactory.getLogger(PushConfigHandler::class.java)

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        val obj = payload.jsonObject
        obj["twamp_packet_count"]?.jsonPrimitive?.int?.let      { twampPacketCount.set(it) }
        obj["traceroute_max_hops"]?.jsonPrimitive?.int?.let     { tracerouteMaxHops.set(it) }
        obj["reporting_threshold_ms"]?.jsonPrimitive?.long?.let { reportingThresholdMs.set(it) }
        obj["buffer_flush_interval_s"]?.jsonPrimitive?.int?.let { bufferFlushIntervalS.set(it) }
        log.info("push_config applied: packetCount=${twampPacketCount.get()} " +
                 "maxHops=${tracerouteMaxHops.get()} thresholdMs=${reportingThresholdMs.get()}")
        return CommandResponse(commandId, agentId, tenantId, "acked")
    }
}
