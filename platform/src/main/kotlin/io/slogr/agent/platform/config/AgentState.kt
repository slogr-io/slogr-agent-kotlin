package io.slogr.agent.platform.config

/**
 * Three-state model for the agent's operational mode.
 *
 * State is determined by the presence and prefix of [SLOGR_API_KEY]:
 * - No key         → ANONYMOUS  (stdout only)
 * - `sk_free_*`    → REGISTERED (OTLP export enabled)
 * - `sk_live_*`    → CONNECTED  (OTLP + RabbitMQ + Pub/Sub)
 * - Invalid format → ANONYMOUS
 */
enum class AgentState { ANONYMOUS, REGISTERED, CONNECTED }

/**
 * Determines [AgentState] from the API key prefix.
 * Evaluated once on daemon startup.
 */
fun determineState(apiKey: String?): AgentState = when {
    apiKey == null                 -> AgentState.ANONYMOUS
    apiKey.startsWith("sk_free_") -> AgentState.REGISTERED
    apiKey.startsWith("sk_live_") -> AgentState.CONNECTED
    else                          -> AgentState.ANONYMOUS  // invalid key format
}
