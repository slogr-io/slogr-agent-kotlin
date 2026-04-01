package io.slogr.agent.platform.buffer

import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Append-only write-ahead log for RabbitMQ publishing.
 *
 * Format: two files in [dataDir]:
 * - `wal.ndjson`  — one JSON line per entry: `{"id":"...","type":"...","data":{...}}`
 * - `wal.acked`   — one acknowledged entry ID per line
 *
 * Entries are written before publishing. On broker ACK the ID is added to `wal.acked`.
 * On startup, `compact()` removes acked entries and resets both files.
 *
 * Max size: [maxRows] rows. When exceeded, oldest unacked entries are evicted.
 */
class WriteAheadLog(
    dataDir: String,
    private val maxRows: Int = 100_000
) {
    private val log     = LoggerFactory.getLogger(WriteAheadLog::class.java)
    private val walFile = File(dataDir, "wal.ndjson")
    private val ackFile = File(dataDir, "wal.acked")
    private val lock    = ReentrantReadWriteLock()

    init {
        File(dataDir).mkdirs()
        compact()
    }

    // ── Write ──────────────────────────────────────────────────────────────────

    /**
     * Append an entry. Returns the new entry's ID.
     * Evicts oldest entries if [maxRows] is exceeded.
     */
    fun append(type: String, dataJson: String): String {
        val id   = UUID.randomUUID().toString()
        val line = """{"id":"$id","type":"$type","data":$dataJson}"""
        lock.write {
            walFile.appendText(line + "\n")
            evictIfOverLimit()
        }
        return id
    }

    // ── Acknowledge ────────────────────────────────────────────────────────────

    /** Mark an entry as published. */
    fun ack(id: String) = lock.write {
        ackFile.appendText(id + "\n")
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    /**
     * Returns all unacknowledged entries as `(id, type, rawDataJson)` triples,
     * ordered from oldest to newest.
     */
    fun unackedEntries(): List<WalEntry> = lock.read {
        if (!walFile.exists()) return emptyList()
        val acked = loadAckedIds()
        walFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseEntry(it) }
            .filter { it.id !in acked }
    }

    /** Number of unacknowledged entries. */
    val sizeRows: Int get() = unackedEntries().size

    // ── Compact ────────────────────────────────────────────────────────────────

    /** Remove all acknowledged entries. Called at startup and periodically. */
    fun compact() = lock.write {
        if (!walFile.exists()) return
        val acked   = loadAckedIds()
        val pending = walFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseEntry(it) }
            .filter { it.id !in acked }
        walFile.writeText(pending.joinToString("\n") { it.rawLine } + (if (pending.isNotEmpty()) "\n" else ""))
        ackFile.writeText("")
        log.debug("WAL compacted: ${pending.size} entries remaining")
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun loadAckedIds(): Set<String> =
        if (ackFile.exists()) ackFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    private fun parseEntry(line: String): WalEntry? = try {
        // Extract id — fast path without full JSON parse
        val id   = extractField(line, "id")   ?: return null
        val type = extractField(line, "type") ?: return null
        val dataStart = line.indexOf("\"data\":") + 7
        val data = line.substring(dataStart).trimEnd().trimEnd('}')
        WalEntry(id = id, type = type, dataJson = data, rawLine = line)
    } catch (_: Exception) {
        null
    }

    private fun extractField(json: String, field: String): String? {
        val key  = "\"$field\":\""
        val start = json.indexOf(key).takeIf { it >= 0 } ?: return null
        val s    = start + key.length
        val end  = json.indexOf('"', s).takeIf { it >= 0 } ?: return null
        return json.substring(s, end)
    }

    private fun evictIfOverLimit() {
        val lines = if (walFile.exists()) walFile.readLines().filter { it.isNotBlank() } else return
        if (lines.size <= maxRows) return
        val keep = lines.drop(lines.size - maxRows)
        walFile.writeText(keep.joinToString("\n") + "\n")
        log.warn("WAL evicted ${lines.size - maxRows} oldest entries (limit=$maxRows)")
    }

    // ── Data ───────────────────────────────────────────────────────────────────

    data class WalEntry(
        val id: String,
        val type: String,
        val dataJson: String,
        val rawLine: String
    )
}
