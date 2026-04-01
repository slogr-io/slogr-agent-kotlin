package io.slogr.agent.platform.health

import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded Prometheus metrics endpoint on 127.0.0.1:9090/metrics.
 *
 * Always available — not gated behind an API key. Local scraping only.
 * Starts automatically in daemon mode; does NOT start for one-shot `check` commands.
 *
 * Metric names follow the slogr_ prefix convention and expose agent health data that
 * SREs need before an OTLP pipeline is configured.
 *
 * R2 requirements: R2-PROM-01 (200 OK, valid exposition format), R2-PROM-02 (no key gate),
 * R2-PROM-03 (127.0.0.1 only — never 0.0.0.0).
 */
class PrometheusExporter(private val port: Int = 9090) {

    private val log = LoggerFactory.getLogger(PrometheusExporter::class.java)
    private var server: HttpServer? = null

    // ── Metrics updated by the daemon ─────────────────────────────────────────

    /** Agent state ordinal: 0=ANONYMOUS, 1=REGISTERED, 2=CONNECTED. */
    val agentStateOrdinal = AtomicInteger(0)

    /** Number of currently active measurement sessions. */
    val activeSessions = AtomicInteger(0)

    /** Number of active responder (reflector) sessions. */
    val activeResponderSessions = AtomicInteger(0)

    /** WAL unacknowledged entry count. */
    val walPendingRows = AtomicInteger(0)

    /** WAL size in bytes. */
    val walSizeBytes = AtomicLong(0L)

    /** Epoch milliseconds when the agent started. */
    val startedAtMs = AtomicLong(System.currentTimeMillis())

    /** Latest RTT label data: label → (rtt_ms, loss_pct, jitter_ms). */
    val sessionMetrics = AtomicReference<Map<String, SessionStats>>(emptyMap())

    data class SessionStats(
        val target: String,
        val method: String,
        val rttMs: Double,
        val lossPct: Double,
        val jitterMs: Double
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the embedded HTTP server.
     * Binds to 127.0.0.1 only — never to 0.0.0.0.
     */
    fun start() {
        // Backlog of 0 uses the system default (usually 50).
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext("/metrics") { exchange ->
            try {
                val body = buildMetrics().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } catch (e: Exception) {
                log.error("Error serving /metrics: ${e.message}")
            }
        }
        // Reject all other paths with 404
        s.createContext("/") { exchange ->
            val body = "Not found\n".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.executor = null
        s.start()
        server = s
        log.info("Prometheus metrics available at http://127.0.0.1:$port/metrics")
    }

    /** Stop the HTTP server. */
    fun stop() {
        server?.stop(0)
        server = null
    }

    // ── Prometheus text exposition ────────────────────────────────────────────

    internal fun buildMetrics(): String = buildString {
        val uptimeSec = (System.currentTimeMillis() - startedAtMs.get()) / 1000.0

        appendLine("# HELP slogr_agent_state Agent operating state (0=ANONYMOUS, 1=REGISTERED, 2=CONNECTED)")
        appendLine("# TYPE slogr_agent_state gauge")
        appendLine("slogr_agent_state ${agentStateOrdinal.get()}")

        appendLine("# HELP slogr_agent_uptime_seconds Seconds since agent process started")
        appendLine("# TYPE slogr_agent_uptime_seconds gauge")
        appendLine("slogr_agent_uptime_seconds $uptimeSec")

        appendLine("# HELP slogr_active_sessions Number of active outbound measurement sessions")
        appendLine("# TYPE slogr_active_sessions gauge")
        appendLine("slogr_active_sessions ${activeSessions.get()}")

        appendLine("# HELP slogr_active_responder_sessions Number of active inbound reflector sessions")
        appendLine("# TYPE slogr_active_responder_sessions gauge")
        appendLine("slogr_active_responder_sessions ${activeResponderSessions.get()}")

        appendLine("# HELP slogr_wal_pending_rows Rows buffered in Write-Ahead Log awaiting publish")
        appendLine("# TYPE slogr_wal_pending_rows gauge")
        appendLine("slogr_wal_pending_rows ${walPendingRows.get()}")

        appendLine("# HELP slogr_wal_size_bytes Estimated WAL size in bytes")
        appendLine("# TYPE slogr_wal_size_bytes gauge")
        appendLine("slogr_wal_size_bytes ${walSizeBytes.get()}")

        // Per-session RTT / loss / jitter
        sessionMetrics.get().values.forEach { s ->
            val labels = """target="${s.target}",method="${s.method}""""
            appendLine("# HELP slogr_rtt_ms Latest average RTT in milliseconds")
            appendLine("# TYPE slogr_rtt_ms gauge")
            appendLine("slogr_rtt_ms{$labels} ${s.rttMs}")

            appendLine("# HELP slogr_loss_pct Latest packet loss percentage")
            appendLine("# TYPE slogr_loss_pct gauge")
            appendLine("slogr_loss_pct{$labels} ${s.lossPct}")

            appendLine("# HELP slogr_jitter_ms Latest jitter in milliseconds")
            appendLine("# TYPE slogr_jitter_ms gauge")
            appendLine("slogr_jitter_ms{$labels} ${s.jitterMs}")
        }
    }
}
