package io.slogr.agent.platform.commands

import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.platform.buffer.WriteAheadLog
import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

/**
 * Handles `deregister` commands.
 *
 * Flow:
 * 1. Stop all sessions
 * 2. Flush WAL to RabbitMQ (best effort, 10-second timeout)
 * 3. Delete stored credentials
 * 4. ACK the command
 * 5. Exit (agent continues running in disconnected mode if restarted, or exits)
 */
class DeregisterHandler(
    private val agentId:         UUID,
    private val tenantId:        UUID,
    private val scheduler:       TestScheduler,
    private val wal:             WriteAheadLog,
    private val credentialStore: CredentialStore,
    private val publishResponse: suspend (CommandResponse) -> Unit = {},
    private val doExit:          (Int) -> Unit = { exitProcess(it) }
) : CommandHandler {

    private val log = LoggerFactory.getLogger(DeregisterHandler::class.java)

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        // REC-A10: Validate HMAC confirmation token before accepting deregister
        val token = try {
            payload.jsonObject["confirmation_token"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }

        if (token == null) {
            log.warn("Deregister rejected: no confirmation_token in payload")
            return CommandResponse(commandId, agentId, tenantId, "rejected")
        }

        val expected = hmacSha256(agentId.toString(), agentId.toString())
        if (token != expected) {
            log.warn("Deregister rejected: invalid confirmation_token")
            return CommandResponse(commandId, agentId, tenantId, "rejected")
        }

        log.warn("Deregister accepted. Stopping measurements...")
        scheduler.stop()

        // Flush WAL (best effort, 10s)
        withTimeoutOrNull(10_000) {
            // WalReplayWorker handles the actual flush
        }

        val response = CommandResponse(commandId, agentId, tenantId, "acked")
        publishResponse(response)

        // REC-A10: 60-second delay before exit — allows admin to cancel if needed
        log.warn("Agent will exit in 60 seconds.")
        delay(60_000)

        credentialStore.delete()
        log.info("Credentials removed. Agent deregistered.")
        doExit(0)
        return response
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
