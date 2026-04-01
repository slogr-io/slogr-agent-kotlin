package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import io.slogr.agent.platform.registration.ApiKeyRegistrar
import kotlinx.coroutines.runBlocking

/**
 * Registers this agent with the Slogr backend using an API key.
 *
 * Accepts `sk_live_*` keys only. For mass deployment, set `SLOGR_API_KEY` env var
 * and use `slogr-agent daemon` instead — it auto-registers on startup.
 */
class ConnectCommand(private val ctx: CliContext) : CliktCommand(name = "connect") {
    override fun help(context: Context) = "Register this agent with the Slogr backend"

    private val apiKey: String? by option(
        "--api-key",
        help  = "Live API key (sk_live_*) for registration",
        envvar = "SLOGR_API_KEY"
    )

    override fun run() {
        if (ctx.credentialStore.isConnected()) {
            val existing = ctx.credentialStore.load()!!
            echo("Already connected as ${existing.displayName} (Agent ID: ${existing.agentId})")
            echo("Run 'slogr-agent disconnect' first to re-register.")
            return
        }

        val key = apiKey ?: run {
            echo("Enter your Slogr API key (sk_live_*), or set SLOGR_API_KEY:")
            val entered = readLine()?.trim()
            if (entered.isNullOrBlank()) {
                echo("No API key provided.", err = true)
                throw ProgramResult(1)
            }
            entered
        }

        if (!key.startsWith("sk_live_")) {
            echo("Invalid key format. 'connect' requires a sk_live_* key.", err = true)
            echo("For sk_free_* keys, set SLOGR_API_KEY and run 'slogr-agent daemon'.")
            throw ProgramResult(4)
        }

        runCatching {
            val cred = runBlocking {
                ApiKeyRegistrar(ctx.credentialStore).register(key)
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
