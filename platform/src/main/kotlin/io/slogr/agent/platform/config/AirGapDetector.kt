package io.slogr.agent.platform.config

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Detects whether the agent is running in an air-gapped environment
 * by attempting to resolve `slogr.io`.
 *
 * Detection runs on first call and the result is cached for the process lifetime.
 * This avoids repeated DNS calls and keeps check command latency low.
 */
object AirGapDetector {

    private val log    = LoggerFactory.getLogger(AirGapDetector::class.java)
    private var cached: Boolean? = null

    /**
     * Returns `true` if `slogr.io` cannot be resolved (air-gapped).
     * Result is cached after the first call.
     */
    fun isAirGapped(): Boolean {
        cached?.let { return it }
        val result = runCatching {
            InetAddress.getByName("slogr.io")
            false
        }.getOrElse { e ->
            if (e is UnknownHostException) true else false
        }
        cached = result
        if (result) log.info("Air-gapped environment detected: slogr.io not reachable")
        return result
    }

    /** Clears the cached result. Used in tests. */
    internal fun resetForTest() { cached = null }
}
