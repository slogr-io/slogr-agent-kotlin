package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.slogr.agent.platform.scheduler.TestScheduler
import org.slf4j.LoggerFactory

/**
 * Runs the agent as a background daemon.
 */
class DaemonCommand(private val ctx: CliContext) : CliktCommand(name = "daemon") {
    override fun help(context: Context) = "Run the agent as a background daemon"

    private val log = LoggerFactory.getLogger(DaemonCommand::class.java)

    private val configPath: String by option(
        "--config",
        help = "Path to schedule config (JSON). Uses persisted schedule if not specified."
    ).default("")

    override fun run() {
        val schedule = ctx.scheduleStore.load()
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
