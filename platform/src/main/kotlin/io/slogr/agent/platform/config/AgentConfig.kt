package io.slogr.agent.platform.config

/**
 * Runtime configuration for the agent.
 *
 * All values have sensible defaults. Updated via push_config (Phase 6).
 */
data class AgentConfig(
    /** Maximum number of concurrent measurement sessions. */
    val maxConcurrentSessions: Int = 20,

    /** Default TWAMP target port. */
    val defaultTwampPort: Int = 862,

    /** Maximum traceroute hops. */
    val maxTracerouteHops: Int = 30,

    /** Probes per traceroute hop. */
    val tracerouteProbesPerHop: Int = 2,

    /** Traceroute probe timeout in milliseconds. */
    val tracerouteTimeoutMs: Int = 2000,

    /** Whether to run traceroute automatically in measure(). */
    val tracerouteEnabled: Boolean = true,

    /** Path to MaxMind GeoLite2-ASN MMDB file; null = ASN disabled. */
    val asnDbPath: String? = null,

    /** Directory where credentials and schedule are persisted. */
    val dataDir: String = System.getProperty("slogr.data.dir",
        System.getProperty("user.home") + "/.slogr"),

    /** OTLP endpoint; null = OTLP disabled. */
    val otlpEndpoint: String? = System.getenv("SLOGR_OTLP_ENDPOINT")
)
