package io.slogr.desktop.core.reflectors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Reflector(
    val id: String,
    val region: String,
    val cloud: String,
    val host: String,
    val port: Int = 862,
    val latitude: Double,
    val longitude: Double,
    val tier: String = "free",
) {
    /** Human-readable region name for UI display. */
    val displayName: String
        get() = region
            .replace("-", " ")
            .replace(Regex("\\d+$"), "")
            .trim()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

@Serializable
data class ReflectorDiscoveryResponse(
    val reflectors: List<Reflector>,
    @SerialName("your_region") val yourRegion: String? = null,
    @SerialName("your_ip") val yourIp: String? = null,
)

@Serializable
data class ReflectorCacheData(
    val reflectors: List<Reflector>,
    @SerialName("your_region") val yourRegion: String? = null,
    @SerialName("your_ip") val yourIp: String? = null,
    @SerialName("cached_at_ms") val cachedAtMs: Long,
)
