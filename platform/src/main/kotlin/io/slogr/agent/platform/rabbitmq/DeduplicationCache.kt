package io.slogr.agent.platform.rabbitmq

import java.security.MessageDigest
import java.util.UUID

/**
 * Per-session SHA-256 deduplication cache.
 *
 * Before publishing, compute SHA-256 of the canonical JSON (sorted fields) for
 * each [MeasurementResult]. If the hash matches the previous hash for this
 * session, the result is a duplicate and should not be published.
 *
 * Thread-safe. Bounded by [maxEntries] (LRU eviction).
 */
class DeduplicationCache(maxEntries: Int = 10_000) {

    private val cache: LinkedHashMap<String, String> =
        object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > maxEntries
        }

    /**
     * Returns true if [canonicalJson] has the same SHA-256 hash as the
     * previously seen value for [sessionId] (i.e. this is a duplicate).
     * Stores the new hash for [sessionId] unconditionally.
     */
    @Synchronized
    fun isDuplicate(sessionId: UUID, canonicalJson: String): Boolean {
        val hash = sha256Hex(canonicalJson)
        val prev = cache.put(sessionId.toString(), hash)
        return prev == hash
    }

    /** Remove the stored hash for [sessionId]. */
    @Synchronized
    fun evict(sessionId: UUID) { cache.remove(sessionId.toString()) }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
