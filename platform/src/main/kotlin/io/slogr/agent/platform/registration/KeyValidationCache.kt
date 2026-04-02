package io.slogr.agent.platform.registration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads and writes the free-key validation cache at [cacheFile].
 *
 * Cache format:
 * ```json
 * { "valid": true, "key_type": "free", "tenant_id": "...", "validated_at": "ISO-8601" }
 * ```
 *
 * TTL is 24 hours. An expired or corrupt cache entry is treated as absent.
 */
class KeyValidationCache(
    private val cacheFile: Path
) {
    private val log  = LoggerFactory.getLogger(KeyValidationCache::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Entry(
        val valid: Boolean,
        @SerialName("key_type") val keyType: String? = null,
        @SerialName("tenant_id") val tenantId: String? = null,
        @SerialName("validated_at") val validatedAt: String
    )

    /** Returns the cached [Entry] if it exists and is < 24 hours old, else null. */
    fun read(): Entry? {
        if (!Files.exists(cacheFile)) return null
        return runCatching {
            val text  = Files.readString(cacheFile)
            val entry = json.decodeFromString(Entry.serializer(), text)
            if (isExpired(entry.validatedAt)) {
                log.debug("Key validation cache expired")
                null
            } else {
                entry
            }
        }.onFailure {
            log.warn("Key validation cache unreadable: ${it.message}")
        }.getOrNull()
    }

    /** Writes [entry] to the cache file, creating parent directories as needed. */
    fun write(entry: Entry) {
        runCatching {
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, json.encodeToString(Entry.serializer(), entry))
        }.onFailure {
            log.warn("Could not write key validation cache: ${it.message}")
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun isExpired(validatedAt: String): Boolean = runCatching {
        val ts = Instant.parse(validatedAt)
        Instant.now().epochSecond - ts.epochSecond > 24 * 3600
    }.onFailure { if (it is DateTimeParseException) log.warn("Cache timestamp unparseable: $validatedAt") }
     .getOrDefault(true)
}
