package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import org.slf4j.LoggerFactory

/**
 * Deregisters this agent and removes stored credentials.
 *
 * Note: Disconnect does NOT call deregister on the SaaS. The agent record stays
 * in Cloud SQL with status "inactive". The user can `slogr-agent connect` again
 * to re-register.
 */
class DisconnectCommand(private val ctx: CliContext) : CliktCommand(name = "disconnect") {
    override fun help(context: Context) = "Deregister this agent and remove stored credentials"

    private val log = LoggerFactory.getLogger(DisconnectCommand::class.java)

    override fun run() {
        if (!ctx.credentialStore.isConnected()) {
            echo("Agent is not connected.")
            return
        }
        val cred = ctx.credentialStore.load()
        if (cred != null) {
            echo("Disconnecting agent ${cred.displayName}...")
        }
        ctx.credentialStore.delete()
        echo("Credentials removed. Agent is now disconnected.")
        echo("Agent will continue running in disconnected mode if daemon is active.")
        log.info("Agent disconnected. Credentials deleted.")
    }
}
