package io.slogr.agent.platform.registration

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.interfaces.CredentialStore
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Automated registration via bootstrap token (marketplace / mesh).
 *
 * Flow:
 * 1. Probe cloud metadata (AWS/GCP/Azure IMDS, 2s timeout each, parallel)
 * 2. POST /api/v1/agents/register with Bearer bootstrap token
 * 3. Store returned [AgentCredential]
 */
class BootstrapRegistrar(
    private val credentialStore: CredentialStore,
    val apiBaseUrl: String = System.getenv("SLOGR_API_URL") ?: "https://api.slogr.io",
    private val agentVersion: String = "1.0.0"
) {
    private val log    = LoggerFactory.getLogger(BootstrapRegistrar::class.java)
    private val json   = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Register using [bootstrapToken]. Stores credential on success.
     * @throws IllegalStateException on HTTP error or malformed response.
     */
    suspend fun register(bootstrapToken: String): AgentCredential {
        log.info("Bootstrap registration starting...")
        val meta    = CloudMetadataProbe.detect()
        val payload = buildRequestBody(meta)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/api/v1/agents/register"))
            .header("Authorization", "Bearer $bootstrapToken")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Registration failed: HTTP ${response.statusCode()} — ${response.body()}")
        }

        val credential = parseCredential(response.body(), ConnectionMethod.BOOTSTRAP_TOKEN)
        credentialStore.store(credential)
        log.info("Bootstrap registration complete. Agent ID: ${credential.agentId}, Display: ${credential.displayName}")
        return credential
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun buildRequestBody(meta: CloudMetadataProbe.CloudMetadata): String =
        """{"cloud":"${meta.cloud}","region":"${meta.region}","instance_id":"${meta.instanceId}","version":"$agentVersion"}"""

    internal fun parseCredential(body: String, method: ConnectionMethod): AgentCredential {
        val root    = json.parseToJsonElement(body).jsonObject
        val rmq     = root["rabbitmq"]?.jsonObject
        return AgentCredential(
            agentId             = UUID.fromString(root["agent_id"]!!.jsonPrimitive.content),
            tenantId            = UUID.fromString(root["tenant_id"]!!.jsonPrimitive.content),
            displayName         = root["display_name"]!!.jsonPrimitive.content,
            jwt                 = root["jwt"]!!.jsonPrimitive.content,
            rabbitmqJwt         = root["rabbitmq_jwt"]?.jsonPrimitive?.content ?: root["jwt"]!!.jsonPrimitive.content,
            rabbitmqHost        = rmq?.get("host")?.jsonPrimitive?.content ?: "mq.slogr.io",
            rabbitmqPort        = rmq?.get("port")?.jsonPrimitive?.content?.toInt() ?: 5671,
            pubsubSubscription  = root["pubsub_subscription"]!!.jsonPrimitive.content,
            issuedAt            = Clock.System.now(),
            connectedVia        = method,
            gcpServiceAccountKey = root["gcp_service_account_key"]?.jsonPrimitive?.content
        )
    }
}
