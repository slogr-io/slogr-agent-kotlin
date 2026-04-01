package io.slogr.agent.platform.health

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.HealthSnapshot
import io.slogr.agent.contracts.PublishStatus
import io.slogr.agent.contracts.interfaces.ResultPublisher
import io.slogr.agent.engine.reflector.ReflectorThreadPool
import io.slogr.agent.platform.buffer.WriteAheadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Publishes a [HealthSnapshot] to RabbitMQ every [intervalMs] milliseconds.
 *
 * Tracks failure counts and determines [PublishStatus]:
 * - GREEN  → 0 recent publish failures
 * - YELLOW → 1–5 consecutive failures
 * - RED    → 6+ consecutive failures
 */
class HealthReporter(
    private val credential: AgentCredential,
    private val publisher: ResultPublisher,
    private val wal: WriteAheadLog,
    private val intervalMs: Long = 60_000,
    /** Optional pool reference; provides R2 reflector health metrics when present. */
    private val reflectorPool: ReflectorThreadPool? = null
) {
    private val log = LoggerFactory.getLogger(HealthReporter::class.java)

    // ── Counters (updated by other components via increment*) ─────────────────

    val twampFailureCount      = AtomicInteger(0)
    val tracerouteFailureCount = AtomicInteger(0)
    val publishFailureCount    = AtomicInteger(0)
    val workerRestartCount     = AtomicInteger(0)

    @Volatile var lastTwampSuccessAt:      Instant? = null
    @Volatile var lastTracerouteSuccessAt: Instant? = null
    @Volatile var agentRestartCount:       Int      = 0

    private var job: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { report() }.onFailure { e ->
                    log.warn("HealthReporter: publish failed: ${e.message}")
                }
            }
        }
    }

    fun stop() { job?.cancel() }

    // ── Snapshot builder ───────────────────────────────────────────────────────

    fun buildSnapshot(): HealthSnapshot = HealthSnapshot(
        agentId                  = credential.agentId,
        tenantId                 = credential.tenantId,
        reportedAt               = Clock.System.now(),
        lastTwampSuccessAt       = lastTwampSuccessAt,
        lastTracerouteSuccessAt  = lastTracerouteSuccessAt,
        publishStatus            = publishStatus(),
        bufferSizeRows           = wal.sizeRows,
        bufferOldestTs           = null,            // WAL doesn't track per-entry timestamps in R1
        twampFailureCount        = twampFailureCount.get(),
        tracerouteFailureCount   = tracerouteFailureCount.get(),
        publishFailureCount      = publishFailureCount.get(),
        workerRestartCount           = workerRestartCount.get(),
        agentRestartCount            = agentRestartCount,
        activeResponderSessions      = reflectorPool?.activeSessionCount ?: 0,
        poolSize                     = reflectorPool?.poolSize            ?: 0,
        poolActiveThreads            = reflectorPool?.activeThreadCount   ?: 0,
        poolQueueDepth               = reflectorPool?.queueDepth          ?: 0
    )

    // ── Internal ───────────────────────────────────────────────────────────────

    private suspend fun report() {
        val snapshot = buildSnapshot()
        val ok = publisher.publishHealth(snapshot)
        if (!ok) publishFailureCount.incrementAndGet()
        log.debug("Health reported: status=${snapshot.publishStatus}, walRows=${snapshot.bufferSizeRows}")
    }

    private fun publishStatus(): PublishStatus {
        val failures = publishFailureCount.get()
        return when {
            failures == 0  -> PublishStatus.OK
            failures <= 5  -> PublishStatus.DEGRADED
            else           -> PublishStatus.FAILING
        }
    }
}
