package io.slogr.agent.platform.registration

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.interfaces.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Refreshes the short-lived RabbitMQ JWT hourly.
 *
 * On success: updates the stored credential and calls [onRefreshed] with the new JWT.
 * On failure: logs warning and retries on the next interval.
 */
class TokenRefresher(
    private val credentialStore: CredentialStore,
    private val onRefreshed: suspend (newJwt: String) -> Unit = {},
    private val intervalMs: Long  = 60 * 60 * 1_000L,   // 1 hour
    private val apiBaseUrl: String = System.getenv("SLOGR_API_URL") ?: "https://api.slogr.io"
) {
    private val log    = LoggerFactory.getLogger(TokenRefresher::class.java)
    private val json   = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { refresh() }.onFailure { e ->
                    log.warn("Token refresh failed: ${e.message}")
                }
            }
        }
    }

    fun stop() { job?.cancel() }

    /** Fetch a fresh RabbitMQ JWT. Returns the new JWT or null on failure. */
    suspend fun refresh(): String? {
        val cred = credentialStore.load() ?: return null
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/api/v1/agents/rabbitmq-token"))
            .header("Authorization", "Bearer ${cred.jwt}")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.warn("RabbitMQ token refresh HTTP ${response.statusCode()}")
            return null
        }
        val newJwt = runCatching {
            json.parseToJsonElement(response.body()).jsonObject["token"]!!.jsonPrimitive.content
        }.getOrNull() ?: return null

        // Persist the updated credential with the new JWT
        val updated = cred.copy(rabbitmqJwt = newJwt)
        credentialStore.store(updated)
        onRefreshed(newJwt)
        log.info("RabbitMQ JWT refreshed successfully")
        return newJwt
    }
}
