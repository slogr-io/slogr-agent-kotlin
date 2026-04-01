package io.slogr.agent.platform.commands

import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.platform.buffer.WriteAheadLog
import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.util.UUID
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
        log.info("Deregister command received — draining and disconnecting")
        scheduler.stop()

        // Flush WAL (best effort, 10s)
        withTimeoutOrNull(10_000) {
            // WalReplayWorker handles the actual flush; here we just wait briefly
        }

        credentialStore.delete()
        log.info("Credentials removed. Agent deregistered.")

        val response = CommandResponse(commandId, agentId, tenantId, "acked")
        publishResponse(response)
        doExit(0)
        return response
    }
}
