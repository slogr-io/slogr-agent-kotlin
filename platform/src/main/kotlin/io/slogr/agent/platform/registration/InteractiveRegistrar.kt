package io.slogr.agent.platform.registration

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.interfaces.CredentialStore
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Interactive registration via API key (direct/non-auto path).
 *
 * Posts to `/v1/agents` using the R2 endpoint.
 * Delegates credential parsing to [ApiKeyRegistrar].
 *
 * For the auto-registration path (mass deployment with `SLOGR_API_KEY` env var),
 * use [ApiKeyRegistrar.register] directly from [DaemonCommand].
 */
class InteractiveRegistrar(
    private val credentialStore: CredentialStore,
    private val apiBaseUrl: String = System.getenv("SLOGR_API_URL") ?: "https://api.slogr.io",
    private val agentVersion: String = "1.0.0"
) {
    private val log       = LoggerFactory.getLogger(InteractiveRegistrar::class.java)
    private val helper    = ApiKeyRegistrar(credentialStore, apiBaseUrl, agentVersion)
    private val client    = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Register using [apiKey]. Stores credential on success.
     * @throws IllegalStateException on HTTP error or malformed response.
     */
    suspend fun register(apiKey: String): AgentCredential {
        log.info("Interactive registration starting...")
        val meta    = CloudMetadataProbe.detect()
        val payload = """{"cloud":"${meta.cloud}","region":"${meta.region}","instance_id":"${meta.instanceId}","version":"$agentVersion"}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/v1/agents"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Registration failed: HTTP ${response.statusCode()} — ${response.body()}")
        }

        val credential = helper.parseCredential(response.body())
        credentialStore.store(credential)
        log.info("Interactive registration complete. Agent ID: ${credential.agentId}")
        return credential
    }
}
