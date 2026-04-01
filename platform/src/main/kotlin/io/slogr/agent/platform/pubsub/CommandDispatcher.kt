package io.slogr.agent.platform.pubsub

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Validates and dispatches incoming Pub/Sub command envelopes.
 *
 * Each command has:
 * - [commandId]   — UUID for correlation
 * - [commandType] — one of the known enum values
 * - [agentId]     — must match this agent's ID
 * - [tenantId]    — must match this agent's tenant
 * - [payload]     — command-type-specific JSON object
 *
 * Returns a [CommandResponse] for every processed envelope (success or failure).
 */
class CommandDispatcher(
    private val agentId:  UUID,
    private val tenantId: UUID,
    private val handlers: Map<String, CommandHandler>
) {
    private val log  = LoggerFactory.getLogger(CommandDispatcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun dispatch(rawJson: String): CommandResponse {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return CommandResponse(null, agentId, tenantId, "failed", error = "invalid JSON")

        val commandId = runCatching {
            UUID.fromString(root["command_id"]?.jsonPrimitive?.content)
        }.getOrNull()

        val commandType = root["command_type"]?.jsonPrimitive?.content
        val payloadAgentId  = runCatching { UUID.fromString(root["agent_id"]?.jsonPrimitive?.content) }.getOrNull()
        val payloadTenantId = runCatching { UUID.fromString(root["tenant_id"]?.jsonPrimitive?.content) }.getOrNull()
        val payload = root["payload"]

        // Validate
        if (commandType == null)
            return CommandResponse(commandId, agentId, tenantId, "failed", error = "missing command_type")
        if (payloadAgentId != null && payloadAgentId != agentId) {
            log.debug("Dropping command for wrong agent: $payloadAgentId (ours: $agentId)")
            return CommandResponse(commandId, agentId, tenantId, "failed", error = "agent_id mismatch")
        }
        if (payloadTenantId != null && payloadTenantId != tenantId)
            return CommandResponse(commandId, agentId, tenantId, "failed", error = "tenant mismatch")

        val handler = handlers[commandType]
            ?: return CommandResponse(commandId, agentId, tenantId, "failed", error = "unknown command type: $commandType")

        return runCatching {
            handler.handle(commandId ?: UUID.randomUUID(), payload ?: json.parseToJsonElement("{}"))
        }.getOrElse { e ->
            log.warn("Command $commandType failed: ${e.message}")
            CommandResponse(commandId, agentId, tenantId, "failed", error = e.message)
        }
    }
}

// ── Contracts ─────────────────────────────────────────────────────────────────

interface CommandHandler {
    suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse
}

data class CommandResponse(
    val commandId:   UUID?,
    val agentId:     UUID,
    val tenantId:    UUID,
    val status:      String,                     // "acked" or "failed"
    val respondedAt: kotlinx.datetime.Instant = Clock.System.now(),
    val result:      Any?    = null,
    val error:       String? = null
)
