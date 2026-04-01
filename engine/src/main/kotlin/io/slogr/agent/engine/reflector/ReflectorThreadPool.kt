package io.slogr.agent.engine.reflector

import io.slogr.agent.engine.twamp.SessionId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Thread pool shared by all active TWAMP reflector sessions.
 *
 * **Motivation**: R1 spawned one [Thread] per session — at 10,000 concurrent
 * sessions that is ~10 GB of stack. R2 replaces this with a bounded pool whose
 * size scales automatically with the host's CPU count:
 * `poolSize = availableProcessors * 2`
 *
 * T2 accuracy is preserved because the JNI `recvmsg()` call captures the
 * kernel-level SO_TIMESTAMPING timestamp at packet arrival time, independently
 * of when a pool worker reads it from the socket buffer.
 *
 * **Session lifecycle:**
 * 1. The TWAMP control plane calls [registerSession] when it accepts a new
 *    REQUEST-TW-SESSION.
 * 2. It then calls [submit] with the [TwampSessionReflector][io.slogr.agent.engine.twamp.responder.TwampSessionReflector]
 *    runnable so a pool worker begins the receive-reflect loop.
 * 3. When the session ends (StopSessions or REFWAIT), the control plane calls
 *    [unregisterSession].
 *
 * Health metrics ([activeSessionCount], [activeThreadCount], [queueDepth]) are
 * polled by [io.slogr.agent.platform.health.HealthReporter] every 60 seconds.
 */
class ReflectorThreadPool {

    private val log = LoggerFactory.getLogger(ReflectorThreadPool::class.java)

    /** Number of worker threads = 2 × available CPU cores. */
    val poolSize: Int = Runtime.getRuntime().availableProcessors() * 2

    private val executor: ThreadPoolExecutor = Executors.newFixedThreadPool(poolSize) { r ->
        Thread(r, "twamp-refl-worker").also { it.isDaemon = true }
    } as ThreadPoolExecutor

    /** Shared scheduler for per-session inactivity timers. */
    val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "twamp-refl-scheduler").also { it.isDaemon = true }
    }

    /** Per-session metadata keyed by session ID. */
    private val sessions = ConcurrentHashMap<SessionId, ReflectorSession>()

    /** Pre-allocated packet buffers shared by all reflectors on this pool. */
    internal val bufferPool: PacketBufferPool = PacketBufferPool(capacity = poolSize * 4)

    // ── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Record that [session] is now active. Must be called before [submit].
     */
    fun registerSession(session: ReflectorSession) {
        sessions[session.sessionId] = session
        log.debug("Session registered: ${session.sessionId} (total=${sessions.size})")
    }

    /**
     * Remove the session record for [sessionId].
     * Safe to call even if the session was never registered.
     */
    fun unregisterSession(sessionId: SessionId) {
        sessions.remove(sessionId)
        log.debug("Session unregistered: $sessionId (total=${sessions.size})")
    }

    /**
     * Submit [reflector] to the pool for execution. Returns a [Future] that
     * the caller can use to cancel or monitor the task.
     */
    fun submit(reflector: Runnable): Future<*> = executor.submit(reflector)

    // ── Health metrics ───────────────────────────────────────────────────────

    /** Number of sessions currently registered (includes sessions awaiting start). */
    val activeSessionCount: Int get() = sessions.size

    /** Number of pool threads currently executing a task. */
    val activeThreadCount: Int get() = executor.activeCount

    /** Number of submitted tasks queued waiting for a free pool thread. */
    val queueDepth: Int get() = executor.queue.size

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Shut down the pool. Waits up to [timeoutSeconds] for in-flight tasks to complete. */
    fun shutdown(timeoutSeconds: Long = 10L) {
        executor.shutdown()
        scheduler.shutdown()
        if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            log.warn("Reflector thread pool did not terminate cleanly in ${timeoutSeconds}s — forcing shutdown")
            executor.shutdownNow()
        }
        if (!scheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
            log.warn("Reflector scheduler did not terminate cleanly in ${timeoutSeconds}s — forcing shutdown")
            scheduler.shutdownNow()
        }
        log.info("Reflector thread pool stopped")
    }
}
