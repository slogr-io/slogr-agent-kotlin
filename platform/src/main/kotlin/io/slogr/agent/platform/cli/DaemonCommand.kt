package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.slogr.agent.contracts.Schedule
import io.slogr.agent.platform.config.AgentState
import io.slogr.agent.platform.registration.ApiKeyRegistrar
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Runs the agent as a background daemon.
 *
 * State-aware startup:
 * - ANONYMOUS:        stdout only; logs nudge toward getting an API key
 * - REGISTERED:       OTLP export enabled; no RabbitMQ/Pub/Sub
 * - CONNECTED + no cred: auto-registers with api.slogr.io (mass deployment path)
 * - CONNECTED + cred:  connects directly using stored credential
 */
class DaemonCommand(private val ctx: CliContext) : CliktCommand(name = "daemon") {
    override fun help(context: Context) = "Run the agent as a background daemon"

    private val log = LoggerFactory.getLogger(DaemonCommand::class.java)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private val configPath: String by option(
        "--config",
        help = "Path to schedule config (JSON). Uses persisted schedule if not specified."
    ).default("")

    override fun run() {
        val state = ctx.config.agentState
        val apiKey = ctx.config.apiKey

        // ── Startup log — mandatory per vault spec ────────────────────────────
        when {
            state == AgentState.CONNECTED && !ctx.credentialStore.isConnected() -> {
                log.info("Auto-registering with api.slogr.io...")
                runCatching {
                    val cred = runBlocking {
                        ApiKeyRegistrar(ctx.credentialStore).register(apiKey!!)
                    }
                    log.info("Connected as ${cred.displayName} (agent_id: ${cred.agentId})")
                    log.info("Starting daemon in CONNECTED mode (RabbitMQ + OTLP + stdout)")
                }.onFailure { e ->
                    log.error("Auto-registration failed: ${e.message}. Starting in ANONYMOUS mode.")
                    log.info("Starting daemon in ANONYMOUS mode (stdout only)")
                    log.info("→ For OTLP export, set SLOGR_API_KEY. Get a free key at https://slogr.io/keys")
                }
            }
            state == AgentState.CONNECTED -> {
                val cred = ctx.credentialStore.load()
                log.info("Connected as ${cred?.displayName ?: "unknown"} (agent_id: ${cred?.agentId ?: "?"})")
                log.info("Starting daemon in CONNECTED mode (RabbitMQ + OTLP + stdout)")
            }
            state == AgentState.REGISTERED -> {
                log.info("Starting daemon in REGISTERED mode (OTLP + stdout)")
            }
            else -> {
                log.info("Starting daemon in ANONYMOUS mode (stdout only)")
                log.info("→ For OTLP export, set SLOGR_API_KEY. Get a free key at https://slogr.io/keys")
            }
        }

        // ── Issue 1 fix: force reflector to bind 0.0.0.0:862 immediately ────
        ctx.engine.start()

        // ── Issue 2 fix: use --config file when provided ──────────────────────
        val schedule = if (configPath.isNotEmpty()) {
            val file = File(configPath)
            if (file.exists()) {
                try {
                    val parsed = json.decodeFromString(Schedule.serializer(), file.readText())
                    log.info("Loaded schedule from $configPath (${parsed.sessions.size} session(s))")
                    parsed
                } catch (e: Exception) {
                    log.warn("Config file $configPath is corrupt, falling back to persisted schedule: ${e.message}")
                    ctx.scheduleStore.load()
                }
            } else {
                log.warn("Config file not found: $configPath — falling back to persisted schedule")
                ctx.scheduleStore.load()
            }
        } else {
            ctx.scheduleStore.load()
        }

        if (schedule == null) {
            log.info("No persisted schedule found — running in responder-only mode")
        } else {
            log.info("Loaded schedule with ${schedule.sessions.size} session(s)")
        }

        val scheduler = TestScheduler(
            engine   = ctx.engine,
            onResult = { cfg, bundle ->
                ctx.otlpExporter.record(bundle, cfg.profile.name)
                log.info("Session ${cfg.pathId} → grade=${bundle.grade}")
            }
        )
        scheduler.start(schedule)

        val shutdownHook = Thread {
            log.info("Shutting down daemon...")
            scheduler.stop()
            ctx.otlpExporter.flush()
            ctx.otlpExporter.shutdown()
            ctx.engine.shutdown()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        log.info("slogr-agent daemon running. Press Ctrl+C to stop.")
        try {
            Thread.currentThread().join()
        } catch (_: InterruptedException) {
            // Interrupted by shutdown hook
        }
    }
}
