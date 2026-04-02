package io.slogr.agent.platform.config

/**
 * Runtime configuration for the agent.
 *
 * All values have sensible defaults. Updated via push_config (Phase 6).
 *
 * [apiKey] is read from `SLOGR_API_KEY` env var. Its prefix determines [agentState]:
 * - `sk_free_*` → REGISTERED (OTLP enabled)
 * - `sk_live_*` → CONNECTED  (OTLP + RabbitMQ + Pub/Sub)
 * - null or invalid → ANONYMOUS (stdout only)
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
    val otlpEndpoint: String? = System.getenv("SLOGR_OTLP_ENDPOINT"),

    /** API key from `SLOGR_API_KEY`. Determines [agentState]. */
    val apiKey: String? = System.getenv("SLOGR_API_KEY")
) {
    /** Operational mode derived from [apiKey] prefix. Evaluated once at construction. */
    val agentState: AgentState = determineState(apiKey)
}
