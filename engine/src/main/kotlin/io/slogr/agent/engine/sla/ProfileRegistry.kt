package io.slogr.agent.engine.sla

import io.slogr.agent.contracts.SlaProfile
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Registry of [SlaProfile] instances loaded from the bundled `profiles.json`.
 *
 * Connected agents can receive updated profiles via `push_config` — call [update]
 * to replace the active set atomically.
 */
object ProfileRegistry {

    private val log = LoggerFactory.getLogger(ProfileRegistry::class.java)

    @Volatile
    private var profiles: Map<String, SlaProfile> = loadDefaults()

    fun get(name: String): SlaProfile? = profiles[name]

    fun all(): List<SlaProfile> = profiles.values.toList()

    /** Replace the active profile set (called from push_config handler). */
    fun update(updated: List<SlaProfile>) {
        profiles = updated.associateBy { it.name }
    }

    private fun loadDefaults(): Map<String, SlaProfile> {
        val stream = ProfileRegistry::class.java.getResourceAsStream("/profiles.json")
        if (stream == null) {
            log.error("profiles.json not found in classpath — no SLA profiles loaded")
            return emptyMap()
        }
        return try {
            val json = stream.bufferedReader().readText()
            val list = Json.decodeFromString<List<SlaProfile>>(json)
            log.info("Loaded {} SLA profiles from profiles.json", list.size)
            list.associateBy { it.name }
        } catch (e: Exception) {
            log.error("Failed to parse profiles.json: {}", e.message)
            emptyMap()
        }
    }
}
