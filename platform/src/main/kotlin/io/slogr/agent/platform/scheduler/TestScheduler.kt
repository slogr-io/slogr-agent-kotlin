package io.slogr.agent.platform.scheduler

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.Schedule
import io.slogr.agent.contracts.SessionConfig
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * Coroutine-based scheduler for measurement sessions.
 *
 * - Reads the active [Schedule] and fires each [SessionConfig] on its
 *   [SessionConfig.intervalSeconds] cadence.
 * - [maxConcurrent] sessions may run simultaneously (default 20).
 * - Each session result is delivered to [onResult].
 *
 * Lifecycle: [start] → update via [updateSchedule] → [stop].
 */
class TestScheduler(
    private val engine: MeasurementEngine,
    private val onResult: (SessionConfig, MeasurementBundle) -> Unit,
    private val maxConcurrent: Int = 20,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val log       = LoggerFactory.getLogger(TestScheduler::class.java)
    private val semaphore = Semaphore(maxConcurrent)

    @Volatile private var schedule: Schedule? = null
    private val sessionJobs = mutableMapOf<String, Job>()

    fun start(initial: Schedule? = null) {
        initial?.let { updateSchedule(it) }
    }

    /** Replace the active schedule. Cancels removed sessions, starts new ones. */
    fun updateSchedule(newSchedule: Schedule) {
        synchronized(sessionJobs) {
            val newPathIds = newSchedule.sessions.map { it.pathId.toString() }.toSet()
            // Cancel sessions no longer in the schedule
            val toRemove = sessionJobs.keys.filter { it !in newPathIds }
            toRemove.forEach { sessionJobs.remove(it)?.cancel() }

            // Start new sessions
            for (session in newSchedule.sessions) {
                val key = session.pathId.toString()
                if (key !in sessionJobs) {
                    sessionJobs[key] = launchSession(session)
                }
            }
            schedule = newSchedule
        }
    }

    /** Stop all running sessions and cancel the scope. */
    fun stop() {
        synchronized(sessionJobs) { sessionJobs.values.forEach { it.cancel() } }
        scope.cancel()
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun launchSession(config: SessionConfig): Job = scope.launch {
        val intervalMs = config.intervalSeconds * 1000L
        while (isActive) {
            val t0 = System.currentTimeMillis()
            semaphore.withPermit {
                try {
                    val bundle = engine.measure(
                        target     = config.targetIp,
                        targetPort = config.targetPort,
                        profile    = config.profile,
                        traceroute = config.tracerouteEnabled
                    )
                    onResult(config, bundle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Session ${config.pathId} failed: ${e.message}")
                }
            }
            val elapsed = System.currentTimeMillis() - t0
            val delay   = (intervalMs - elapsed).coerceAtLeast(0L)
            delay(delay)
        }
    }
}
