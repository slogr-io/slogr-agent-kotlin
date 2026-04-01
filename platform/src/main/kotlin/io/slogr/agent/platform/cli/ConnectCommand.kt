package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import io.slogr.agent.platform.registration.BootstrapRegistrar
import io.slogr.agent.platform.registration.InteractiveRegistrar
import kotlinx.coroutines.runBlocking

/**
 * Registers this agent with the Slogr backend.
 *
 * Two paths:
 * - Bootstrap token (`--bootstrap-token` or `SLOGR_BOOTSTRAP_TOKEN` env var): automated
 * - API key (`--api-key` or `SLOGR_API_KEY` env var, or interactive prompt): interactive
 */
class ConnectCommand(private val ctx: CliContext) : CliktCommand(name = "connect") {
    override fun help(context: Context) = "Register this agent with the Slogr backend"

    private val apiKey: String?         by option("--api-key",         help = "API key for interactive registration",
                                                   envvar = "SLOGR_API_KEY")
    private val bootstrapToken: String? by option("--bootstrap-token", help = "Bootstrap token for automated registration",
                                                   envvar = "SLOGR_BOOTSTRAP_TOKEN")

    override fun run() {
        if (ctx.credentialStore.isConnected()) {
            val existing = ctx.credentialStore.load()!!
            echo("Already connected as ${existing.displayName} (Agent ID: ${existing.agentId})")
            echo("Run 'slogr-agent disconnect' first to re-register.")
            return
        }

        val token = bootstrapToken
        val key   = apiKey

        runCatching {
            val cred = runBlocking {
                when {
                    token != null -> {
                        echo("Registering agent with bootstrap token...")
                        BootstrapRegistrar(ctx.credentialStore).register(token)
                    }
                    key != null -> {
                        echo("Registering agent with API key...")
                        InteractiveRegistrar(ctx.credentialStore).register(key)
                    }
                    else -> {
                        // Interactive prompt
                        echo("Enter your Slogr API key (or set SLOGR_API_KEY):")
                        val entered = readLine()?.trim()
                        if (entered.isNullOrBlank()) {
                            echo("No API key provided.", err = true)
                            throw ProgramResult(1)
                        }
                        echo("Registering agent...")
                        InteractiveRegistrar(ctx.credentialStore).register(entered)
                    }
                }
            }
            echo("Connected as ${cred.displayName}")
            echo("Agent ID: ${cred.agentId}")
            echo("Run 'slogr-agent daemon' to start the agent in connected mode.")
        }.onFailure { e ->
            if (e is ProgramResult) throw e
            echo("Registration failed: ${e.message}", err = true)
            throw ProgramResult(1)
        }
    }
}
