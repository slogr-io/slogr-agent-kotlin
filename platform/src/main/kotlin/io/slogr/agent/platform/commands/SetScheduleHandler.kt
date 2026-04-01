package io.slogr.agent.platform.commands

import io.slogr.agent.contracts.Schedule
import io.slogr.agent.contracts.SessionConfig
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import io.slogr.agent.platform.scheduler.ScheduleStore
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.UUID

/**
 * Handles `set_schedule` commands.
 *
 * Replaces the agent's test schedule with the one provided in the payload.
 * Persists the new schedule to disk and restarts the scheduler.
 *
 * Payload fields:
 * - `targets`           — array of `{path_id, target_ip, target_port?}`
 * - `profile`           — profile name
 * - `interval_seconds`  — how often to run each session
 * - `test_type`         — "twamp" | "traceroute" | "both"
 */
class SetScheduleHandler(
    private val agentId:         UUID,
    private val tenantId:        UUID,
    private val scheduler:       TestScheduler,
    private val scheduleStore:   ScheduleStore,
    private val profileRegistry: (name: String) -> SlaProfile?
) : CommandHandler {

    private val log = LoggerFactory.getLogger(SetScheduleHandler::class.java)

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        val obj             = payload.jsonObject
        val targets         = obj["targets"]?.jsonArray
            ?: return CommandResponse(commandId, agentId, tenantId, "failed", error = "missing targets")
        val profileName     = obj["profile"]?.jsonPrimitive?.content ?: "internet"
        val intervalSeconds = obj["interval_seconds"]?.jsonPrimitive?.int ?: 300
        val testType        = obj["test_type"]?.jsonPrimitive?.content ?: "twamp"

        val profile = profileRegistry(profileName)
            ?: return CommandResponse(commandId, agentId, tenantId, "failed", error = "unknown profile: $profileName")

        val sessions = targets.mapNotNull { t ->
            val tObj = t.jsonObject
            val ip   = tObj["target_ip"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val port = tObj["target_port"]?.jsonPrimitive?.int ?: 862
            val pid  = runCatching { UUID.fromString(tObj["path_id"]!!.jsonPrimitive.content) }
                           .getOrElse { UUID.randomUUID() }
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return@mapNotNull null

            val rawPorts = tObj["tcp_probe_ports"]?.jsonArray?.map { it.jsonPrimitive.int }
                ?: listOf(443)
            val probePorts = if (rawPorts.size > MAX_TCP_PROBE_PORTS) {
                log.warn(
                    "tcp_probe_ports has ${rawPorts.size} entries for path $pid; " +
                    "capped at $MAX_TCP_PROBE_PORTS"
                )
                rawPorts.take(MAX_TCP_PROBE_PORTS)
            } else rawPorts

            SessionConfig(
                pathId             = pid,
                targetIp           = addr,
                targetPort         = port,
                profile            = profile,
                intervalSeconds    = intervalSeconds,
                tracerouteEnabled  = testType != "twamp",
                tcpProbePorts      = probePorts
            )
        }

        val schedule = Schedule(sessions = sessions, receivedAt = Clock.System.now(), commandId = commandId)
        scheduler.updateSchedule(schedule)
        scheduleStore.save(schedule)

        log.info("set_schedule: ${sessions.size} session(s) applied")
        return CommandResponse(commandId, agentId, tenantId, "acked")
    }

    private companion object {
        /** ADR-040: max 5 ports per target to prevent accidental port-scan bursts. */
        const val MAX_TCP_PROBE_PORTS = 5
    }
}
