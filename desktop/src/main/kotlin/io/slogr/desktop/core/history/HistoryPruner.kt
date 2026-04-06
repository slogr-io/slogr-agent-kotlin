package io.slogr.desktop.core.history

import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/**
 * Background coroutine that prunes measurement history older than 24 hours.
 * Runs every hour on [Dispatchers.IO].
 */
class HistoryPruner(private val store: LocalHistoryStore) {

    private val log = LoggerFactory.getLogger(HistoryPruner::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (isActive) {
                try {
                    val cutoff = Clock.System.now() - 24.hours
                    val deleted = store.pruneOlderThan(cutoff)
                    if (deleted > 0) {
                        log.info("Pruned {} history entries older than 24h", deleted)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("History pruning failed: {}", e.message)
                }
                delay(1.hours)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
