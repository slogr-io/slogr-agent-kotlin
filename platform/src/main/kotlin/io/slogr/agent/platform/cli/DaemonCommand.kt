package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.slogr.agent.contracts.Schedule
import io.slogr.agent.engine.asn.Ip2AsnResolver
import io.slogr.agent.platform.buffer.WriteAheadLog
import io.slogr.agent.platform.commands.SetScheduleHandler
import io.slogr.agent.platform.config.AgentState
import io.slogr.agent.platform.health.HealthReporter
import io.slogr.agent.platform.pubsub.CommandDispatcher
import io.slogr.agent.platform.pubsub.PubSubSubscriber
import io.slogr.agent.platform.rabbitmq.RabbitMqConnection
import io.slogr.agent.platform.rabbitmq.RabbitMqPublisher
import io.slogr.agent.platform.registration.ApiKeyRegistrar
import io.slogr.agent.platform.registration.TokenRefresher
import io.slogr.agent.platform.scheduler.BuiltinProfiles
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 *
 * CONNECTED mode wires four long-lived services on the publishScope:
 *   1. [RabbitMqConnection.reconnectLoop] — reconnect with JWT refresh callback
 *   2. [TokenRefresher]                   — refresh RabbitMQ JWT every 10 minutes
 *   3. [HealthReporter]                   — publish HealthSnapshot every 60 seconds
 *   4. [PubSubSubscriber]                 — poll Pub/Sub for set_schedule / run_test / etc.
 *
 * Composition-root presence of each service is asserted by DaemonCommandWiringTest to
 * prevent regressions like slogr-io/slogr-agent-kotlin#27/#28/#29.
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

        // ── RabbitMQ wiring — CONNECTED mode only ────────────────────────────
        var publisher: RabbitMqPublisher? = null
        var rabbitConn: RabbitMqConnection? = null
        var tokenRefresher: TokenRefresher? = null
        var healthReporter: HealthReporter? = null
        var pubsubSubscriber: PubSubSubscriber? = null
        val publishScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        if (state == AgentState.CONNECTED) {
            val cred = ctx.credentialStore.load()
            if (cred != null && apiKey != null) {
                val wal = WriteAheadLog(ctx.config.dataDir)
                rabbitConn = RabbitMqConnection(cred)

                // TokenRefresher is created BEFORE reconnectLoop so its refresh()
                // can feed the loop on every reconnect attempt.
                tokenRefresher = TokenRefresher(
                    credentialStore = ctx.credentialStore,
                    apiKey          = apiKey,
                    onRefreshed     = { newJwt -> rabbitConn?.updateJwt(newJwt) }
                )
                tokenRefresher.start(publishScope)

                runCatching { rabbitConn.connect() }.onFailure { e ->
                    log.warn("RabbitMQ initial connect failed: ${e.message} — will retry via reconnect loop")
                }
                rabbitConn.reconnectLoop(publishScope) { tokenRefresher!!.refresh() }
                publisher = RabbitMqPublisher(cred, wal, rabbitConn)
                log.info("RabbitMQ publisher wired for agent ${cred.agentId}")

                // HealthReporter: publishes HealthSnapshot every 60s on publishScope.
                healthReporter = HealthReporter(
                    credential = cred,
                    publisher  = publisher,
                    wal        = wal
                )
                healthReporter.start(publishScope)
                log.info("HealthReporter started (60s interval)")
            }
        }

        // ── ASN database periodic refresh ────────────────────────────────────
        ctx.asnDatabaseUpdater?.startPeriodicRefresh(publishScope) { newFile ->
            Ip2AsnResolver.fromFile(newFile)?.let { resolver ->
                ctx.swappableAsnResolver?.swap(resolver)
                log.info("ASN database refreshed from {}", newFile.name)
            }
        }

        // ── Force reflector to bind 0.0.0.0:862 immediately ───────────────────
        ctx.engine.start()

        // ── Load initial schedule (from --config file, else persisted) ────────
        val initialSchedule = if (configPath.isNotEmpty()) {
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

        if (initialSchedule == null) {
            log.info("No persisted schedule found — running in responder-only mode until a set_schedule command arrives")
        } else {
            log.info("Loaded schedule with ${initialSchedule.sessions.size} session(s)")
        }

        val scheduler = TestScheduler(
            engine   = ctx.engine,
            onResult = { cfg, bundle ->
                ctx.otlpExporter.record(bundle, cfg.profile.name)
                log.info("Session ${cfg.pathId} → grade=${bundle.grade}")
                publisher?.let { pub ->
                    publishScope.launch {
                        try {
                            pub.publishMeasurement(bundle.twamp)
                            bundle.traceroute?.let { tr -> pub.publishTraceroute(tr) }
                        } catch (e: Exception) {
                            log.warn("RabbitMQ publish failed for session ${cfg.pathId}: ${e.message}")
                        }
                    }
                }
            }
        )
        scheduler.start(initialSchedule)

        // ── Pub/Sub command channel — CONNECTED mode only, wired AFTER scheduler ──
        if (state == AgentState.CONNECTED) {
            val cred = ctx.credentialStore.load()
            if (cred != null) {
                val setScheduleHandler = SetScheduleHandler(
                    agentId         = cred.agentId,
                    tenantId        = cred.tenantId,
                    scheduler       = scheduler,
                    scheduleStore   = ctx.scheduleStore,
                    profileRegistry = { name -> BuiltinProfiles.byName(name) }
                )
                val dispatcher = CommandDispatcher(
                    agentId  = cred.agentId,
                    tenantId = cred.tenantId,
                    handlers = mapOf("set_schedule" to setScheduleHandler)
                )
                pubsubSubscriber = PubSubSubscriber(
                    credential = cred,
                    projectId  = System.getenv("GCP_PROJECT") ?: "slogr-app",
                    dispatcher = dispatcher
                )
                pubsubSubscriber.start(publishScope)
                log.info("Pub/Sub subscriber started for agent ${cred.agentId}")
            }
        }

        val shutdownHook = Thread {
            log.info("Shutting down daemon...")
            ctx.asnDatabaseUpdater?.stopPeriodicRefresh()
            pubsubSubscriber?.stop()
            healthReporter?.stop()
            tokenRefresher?.stop()
            scheduler.stop()
            runBlocking { publisher?.flush() }
            rabbitConn?.close()
            publishScope.cancel()
            ctx.otlpExporter.flush()
            ctx.otlpExporter.shutdown()
            ctx.engine.shutdown()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        log.info("slogr-agent daemon running. Press Ctrl+C to stop.")
        // Block the main thread so daemon threads (reflector, controller) stay alive.
        // Loop on InterruptedException — spurious interrupts must not exit daemon mode;
        // only a JVM shutdown (SIGTERM → shutdown hook) should terminate the process.
        while (true) {
            try {
                Thread.currentThread().join()
                break
            } catch (_: InterruptedException) {
                log.debug("Main thread interrupted — continuing daemon loop")
            }
        }
    }
}
