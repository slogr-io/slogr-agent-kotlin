package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import io.slogr.agent.platform.config.AgentState

class StatusCommand(private val ctx: CliContext) : CliktCommand(name = "status") {
    override fun help(context: Context) = "Show agent connection status"

    override fun run() {
        val state  = ctx.config.agentState
        val apiKey = ctx.config.apiKey
        val cred   = ctx.credentialStore.load()

        val modeLabel = when (state) {
            AgentState.ANONYMOUS  -> "ANONYMOUS (stdout only)"
            AgentState.REGISTERED -> "REGISTERED (OTLP + stdout)"
            AgentState.CONNECTED  -> "CONNECTED (RabbitMQ + OTLP + stdout)"
        }

        val statusLabel = when {
            state == AgentState.CONNECTED && cred != null -> "Connected"
            state == AgentState.ANONYMOUS -> "Disconnected"
            else -> state.name
        }
        echo("Status:     $statusLabel")
        echo("Mode:       $modeLabel")

        if (apiKey != null) {
            val maskedKey = if (apiKey.length > 4) "${apiKey.take(8)}...${apiKey.takeLast(4)}" else "****"
            echo("Key:        $maskedKey")
        }

        if (cred != null) {
            echo("Agent ID:   ${cred.agentId}")
            echo("Display:    ${cred.displayName}")
            echo("Tenant ID:  ${cred.tenantId}")
            echo("RabbitMQ:   ${cred.rabbitmqHost}:${cred.rabbitmqPort}")
            echo("Pub/Sub:    ${cred.pubsubSubscription}")
        } else if (state == AgentState.ANONYMOUS) {
            echo("")
            echo("  Run 'slogr-agent connect --api-key sk_live_...' to register.")
            echo("  Or set SLOGR_API_KEY=sk_free_... to enable OTLP export.")
        }
    }
}
