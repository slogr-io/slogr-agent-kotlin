package io.slogr.agent.platform.buffer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Drains unacknowledged WAL entries to the publisher at [ratePerSecond] entries/second.
 *
 * Started when RabbitMQ reconnects. Stopped when the WAL is empty or the
 * connection drops again.
 */
class WalReplayWorker(
    private val wal: WriteAheadLog,
    private val publish: suspend (type: String, dataJson: String) -> Boolean,
    private val ratePerSecond: Int = 10
) {
    private val log             = LoggerFactory.getLogger(WalReplayWorker::class.java)
    private val intervalMs      = 1_000L / ratePerSecond
    @Volatile private var job: Job? = null

    /** Start replaying in [scope]. No-op if already running. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            log.info("WAL replay started (rate=${ratePerSecond}/s)")
            var replayed = 0
            while (isActive) {
                val entries = wal.unackedEntries()
                if (entries.isEmpty()) break
                val entry = entries.first()
                val ok = runCatching { publish(entry.type, entry.dataJson) }.getOrDefault(false)
                if (ok) {
                    wal.ack(entry.id)
                    replayed++
                    if (replayed % 100 == 0) log.info("WAL replay progress: $replayed entries published")
                } else {
                    log.warn("WAL replay: publish failed for ${entry.id}, will retry")
                    delay(3_000)
                }
                delay(intervalMs)
            }
            log.info("WAL replay complete: $replayed entries published")
            wal.compact()
        }
    }

    /** Cancel an in-progress replay. */
    fun stop() { job?.cancel() }

    val isRunning: Boolean get() = job?.isActive == true
}
