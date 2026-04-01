package io.slogr.agent.platform.registration

import io.slogr.agent.platform.config.AgentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Validates a `sk_free_*` key by calling `GET /v1/keys/validate`.
 *
 * Rules:
 * - Valid cached result (< 24h) → use cache, no network call
 * - 200 OK → REGISTERED; cache result
 * - 401 response → ANONYMOUS; cache invalid result
 * - Network error → trust key format, return REGISTERED (air-gap support)
 *
 * The [tenantId] from a successful validation is exposed for OTLP resource attributes.
 */
class FreeKeyValidator(
    private val cache: KeyValidationCache,
    private val apiBaseUrl: String = System.getenv("SLOGR_API_URL") ?: "https://api.slogr.io"
) {
    private val log    = LoggerFactory.getLogger(FreeKeyValidator::class.java)
    private val json   = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class ValidationResult(
        val state: AgentState,
        val tenantId: String?
    )

    /**
     * Validates [apiKey] (must start with `sk_free_*`).
     * Returns [ValidationResult] with the resolved [AgentState] and optional tenant ID.
     */
    suspend fun validate(apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        require(apiKey.startsWith("sk_free_")) {
            "FreeKeyValidator only handles sk_free_* keys"
        }

        // Check cache first
        val cached = cache.read()
        if (cached != null) {
            log.debug("Using cached key validation (valid=${cached.valid})")
            return@withContext if (cached.valid)
                ValidationResult(AgentState.REGISTERED, cached.tenantId)
            else
                ValidationResult(AgentState.ANONYMOUS, null)
        }

        // Network validation
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/v1/keys/validate"))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        runCatching {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> {
                    val root     = json.parseToJsonElement(response.body()).jsonObject
                    val tenantId = root["tenant_id"]?.jsonPrimitive?.content
                    cache.write(KeyValidationCache.Entry(
                        valid       = true,
                        keyType     = "free",
                        tenantId    = tenantId,
                        validatedAt = Instant.now().toString()
                    ))
                    log.info("Free key validated. State: REGISTERED")
                    ValidationResult(AgentState.REGISTERED, tenantId)
                }
                401, 403 -> {
                    log.warn("Free key invalid or revoked (HTTP ${response.statusCode()}). Running ANONYMOUS.")
                    cache.write(KeyValidationCache.Entry(
                        valid       = false,
                        validatedAt = Instant.now().toString()
                    ))
                    ValidationResult(AgentState.ANONYMOUS, null)
                }
                else -> {
                    log.warn("Unexpected status ${response.statusCode()} validating key. Trusting format.")
                    ValidationResult(AgentState.REGISTERED, null)
                }
            }
        }.getOrElse { e ->
            // Network error — trust key format, support air-gapped deployments
            log.warn("Could not reach api.slogr.io for key validation: ${e.message}. Trusting key format.")
            ValidationResult(AgentState.REGISTERED, null)
        }
    }
}
