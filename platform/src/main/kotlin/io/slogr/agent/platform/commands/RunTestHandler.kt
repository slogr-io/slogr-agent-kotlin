package io.slogr.agent.platform.commands

import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.platform.pubsub.CommandDispatcher
import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.UUID

/**
 * Handles `run_test` commands. Runs a single on-demand measurement and returns the result.
 *
 * Payload fields:
 * - `target_ip`   — IPv4/IPv6 address string
 * - `profile`     — profile name (looked up from [profileRegistry])
 * - `test_type`   — "twamp" | "traceroute" | "both" (default "twamp")
 * - `path_id`     — UUID (optional, for correlation)
 */
class RunTestHandler(
    private val agentId:  UUID,
    private val tenantId: UUID,
    private val engine:   MeasurementEngine,
    private val profileRegistry: (name: String) -> SlaProfile?
) : CommandHandler {

    private val log = LoggerFactory.getLogger(RunTestHandler::class.java)

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        val obj        = payload.jsonObject
        val targetStr  = obj["target_ip"]?.jsonPrimitive?.content
            ?: return error(commandId, "missing target_ip")
        val profileName = obj["profile"]?.jsonPrimitive?.content ?: "internet"
        val testType   = obj["test_type"]?.jsonPrimitive?.content ?: "twamp"

        val target  = runCatching { InetAddress.getByName(targetStr) }.getOrNull()
            ?: return error(commandId, "invalid target_ip: $targetStr")
        val profile = profileRegistry(profileName)
            ?: return error(commandId, "unknown profile: $profileName")

        val traceroute = testType == "traceroute" || testType == "both"

        log.info("run_test: target=$targetStr profile=$profileName type=$testType")
        val bundle = engine.measure(target = target, profile = profile, traceroute = traceroute)

        val result = mapOf(
            "test_type"    to testType,
            "p99_ms"       to bundle.twamp.fwdMaxRttMs,
            "p50_ms"       to bundle.twamp.fwdAvgRttMs,
            "loss_pct"     to bundle.twamp.fwdLossPct,
            "jitter_ms"    to bundle.twamp.fwdJitterMs,
            "grade"        to bundle.grade.name,
            "completed_at" to Clock.System.now().toString()
        )
        return CommandResponse(commandId, agentId, tenantId, "acked", result = result)
    }

    private fun error(commandId: UUID, msg: String) =
        CommandResponse(commandId, agentId, tenantId, "failed", error = msg)
}
