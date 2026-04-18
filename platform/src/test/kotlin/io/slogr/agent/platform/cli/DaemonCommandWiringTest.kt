package io.slogr.agent.platform.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Static regression test: verifies that every long-lived service the agent relies on
 * is actually referenced from the composition root (DaemonCommand.kt).
 *
 * Closes slogr-io/slogr-agent-kotlin #27 (PubSubSubscriber), #28 (TokenRefresher),
 * #29 (HealthReporter) — all three classes had been implemented but never wired.
 * Without this test the pattern can silently recur.
 *
 * Why static: a runtime integration test for the daemon requires mocking GCP clients,
 * RabbitMQ, TWAMP engine threads, OTLP exporter, and coroutine scopes. Static source
 * inspection catches wiring regressions at essentially zero cost and is deterministic.
 */
class DaemonCommandWiringTest {

    private val daemonSource: String by lazy {
        val path = "src/main/kotlin/io/slogr/agent/platform/cli/DaemonCommand.kt"
        File(path).readText()
    }

    @Test
    fun `DaemonCommand references PubSubSubscriber`() {
        assertTrue(
            daemonSource.contains("PubSubSubscriber("),
            "DaemonCommand must instantiate PubSubSubscriber — otherwise agent ignores all commands. See #27."
        )
        assertTrue(
            daemonSource.contains("pubsubSubscriber?.stop()") || daemonSource.contains("pubsubSubscriber.stop()"),
            "DaemonCommand shutdown hook must stop PubSubSubscriber — otherwise coroutine leaks on exit."
        )
    }

    @Test
    fun `DaemonCommand references TokenRefresher`() {
        assertTrue(
            daemonSource.contains("TokenRefresher("),
            "DaemonCommand must instantiate TokenRefresher — otherwise RabbitMQ JWT expires after 15min. See #28."
        )
        assertTrue(
            daemonSource.contains("tokenRefresher"),
            "TokenRefresher must be kept in scope so it can feed reconnectLoop and stop on shutdown."
        )
    }

    @Test
    fun `DaemonCommand references HealthReporter`() {
        assertTrue(
            daemonSource.contains("HealthReporter("),
            "DaemonCommand must instantiate HealthReporter — otherwise no agent_health rows reach ClickHouse. See #29."
        )
        assertTrue(
            daemonSource.contains(".start(publishScope)"),
            "HealthReporter.start must be called so its 60s publish loop runs."
        )
    }

    @Test
    fun `DaemonCommand references CommandDispatcher with at least set_schedule handler`() {
        assertTrue(
            daemonSource.contains("CommandDispatcher("),
            "DaemonCommand must instantiate CommandDispatcher — required by PubSubSubscriber."
        )
        assertTrue(
            daemonSource.contains("SetScheduleHandler("),
            "DaemonCommand must register SetScheduleHandler — otherwise set_schedule commands are rejected as unknown."
        )
    }

    @Test
    fun `reconnectLoop is passed a JWT refresher lambda`() {
        // The historical bug was `reconnectLoop(publishScope) { null }` — always returned null.
        // Require a reference to tokenRefresher OR a non-null-returning lambda inside reconnectLoop.
        val looksLikeRealRefresh = daemonSource.contains(Regex("""reconnectLoop\([^)]*\)\s*\{\s*tokenRefresher[!?]*\.refresh\("""))
        assertTrue(
            looksLikeRealRefresh,
            "reconnectLoop must be passed a lambda that calls tokenRefresher.refresh() — not { null }. See #28."
        )
    }
}
