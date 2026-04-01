package io.slogr.agent.platform.scheduler

import io.slogr.agent.contracts.Schedule
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Persists the active [Schedule] to disk as JSON.
 *
 * - File: `$dataDir/schedule.json`
 * - If the file is corrupt or missing: returns null (empty schedule).
 * - Thread-safe: reads and writes are synchronized.
 */
class ScheduleStore(dataDir: String) {

    private val log  = LoggerFactory.getLogger(ScheduleStore::class.java)
    private val file = File(dataDir, "schedule.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): Schedule? = synchronized(this) {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(Schedule.serializer(), file.readText())
        } catch (e: Exception) {
            log.warn("schedule.json is corrupt, ignoring: ${e.message}")
            null
        }
    }

    fun save(schedule: Schedule) = synchronized(this) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Schedule.serializer(), schedule))
    }

    fun clear() = synchronized(this) { file.delete() }
}
