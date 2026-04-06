package io.slogr.desktop.core.reflectors

import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ReflectorCache(private val dataDir: Path) {

    private val cacheFile = dataDir.resolve("reflectors.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        const val TTL_MS = 24L * 60 * 60 * 1000 // 24 hours
    }

    fun load(): ReflectorCacheData? {
        if (!cacheFile.exists()) return null
        return try {
            json.decodeFromString<ReflectorCacheData>(cacheFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(response: ReflectorDiscoveryResponse) {
        val data = ReflectorCacheData(
            reflectors = response.reflectors,
            yourRegion = response.yourRegion,
            yourIp = response.yourIp,
            cachedAtMs = System.currentTimeMillis(),
        )
        dataDir.createDirectories()
        cacheFile.writeText(json.encodeToString(ReflectorCacheData.serializer(), data))
    }

    fun isExpired(): Boolean {
        val data = load() ?: return true
        return System.currentTimeMillis() - data.cachedAtMs > TTL_MS
    }

    fun clear() {
        if (cacheFile.exists()) {
            cacheFile.toFile().delete()
        }
    }
}
