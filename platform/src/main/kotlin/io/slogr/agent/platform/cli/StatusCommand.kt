package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class StatusCommand(private val ctx: CliContext) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Show agent connection status"

    override fun run() {
        val cred = ctx.credentialStore.load()
        if (cred == null) {
            echo("Status: Disconnected")
            echo("  Run 'slogr-agent connect' to register this agent.")
        } else {
            echo("Status: Connected")
            echo("  Agent ID:  ${cred.agentId}")
            echo("  Tenant ID: ${cred.tenantId}")
            echo("  Name:      ${cred.displayName}")
            echo("  Broker:    ${cred.rabbitmqHost}:${cred.rabbitmqPort}")
        }
    }
}
