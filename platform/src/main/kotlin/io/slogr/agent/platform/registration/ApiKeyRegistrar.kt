package io.slogr.agent.platform.registration

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.platform.credential.MachineIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * R2 registration: API key → POST /v1/agents.
 *
 * Replaces R1 [BootstrapRegistrar]. Handles both:
 * - Explicit path: `slogr-agent connect --api-key sk_live_...`
 * - Auto path: daemon detects `sk_live_*` key with no stored credential
 *
 * Security:
 * - Validates key format before any network call (must be `sk_live_*`)
 * - Machine fingerprint is SHA-256(mac + hostname) — stable per machine
 */
class ApiKeyRegistrar(
    private val credentialStore: CredentialStore,
    val apiBaseUrl: String = System.getenv("SLOGR_API_URL") ?: "https://api.slogr.io",
    private val agentVersion: String = "1.0.3"
) {
    private val log    = LoggerFactory.getLogger(ApiKeyRegistrar::class.java)
    private val json   = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * Register this agent using [apiKey]. Stores credential on success.
     *
     * @throws IllegalStateException on 4xx/5xx HTTP response
     * @throws IllegalArgumentException if key format is invalid
     */
    suspend fun register(apiKey: String): AgentCredential = withContext(Dispatchers.IO) {
        require(apiKey.startsWith("sk_live_")) {
            "Invalid key format. Must start with sk_live_. Get a valid key at https://slogr.io"
        }
        log.info("Registering with api.slogr.io...")
        val meta    = CloudMetadataProbe.detect()
        val payload = buildBody(meta)

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

        val credential = parseCredential(response.body())
        credentialStore.store(credential)
        log.info("Connected as ${credential.displayName} (agent_id: ${credential.agentId})")
        credential
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun buildBody(meta: CloudMetadataProbe.CloudMetadata): String {
        val fingerprint = sha256Hex(MachineIdentity.fingerprint())
        val hostname    = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
        val privateIp   = runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("unknown")
        val publicIp    = detectPublicIp() ?: privateIp
        val mac         = firstMac() ?: "00:00:00:00:00:00"
        val osName      = System.getProperty("os.name", "unknown").lowercase()
        val osVersion   = System.getProperty("os.version", "unknown")
        val osArch      = System.getProperty("os.arch", "unknown")
        val jvmVersion  = System.getProperty("java.version", "unknown")
        val cpuCores    = Runtime.getRuntime().availableProcessors()
        val memMb       = ManagementFactory.getMemoryMXBean().heapMemoryUsage.max
            .let { if (it < 0) 0L else it / (1024 * 1024) }
        val nativeMode  = runCatching {
            Class.forName("io.slogr.agent.native.SlogrNative"); true
        }.getOrDefault(false)

        return """{"machine_fingerprint":"$fingerprint","cloud":"${meta.cloud}","region":"${meta.region}","instance_id":"${meta.instanceId}","agent_version":"$agentVersion","public_ip":"$publicIp","private_ip":"$privateIp","hostname":"$hostname","os_name":"$osName","os_version":"$osVersion","os_arch":"$osArch","mac_address":"$mac","runtime":"jvm","runtime_version":"$jvmVersion","native_mode":$nativeMode,"cpu_cores":$cpuCores,"memory_mb":$memMb}"""
    }

    internal fun parseCredential(body: String): AgentCredential {
        val root = json.parseToJsonElement(body).jsonObject
        val jwt  = root["credential"]!!.jsonPrimitive.content
        return AgentCredential(
            agentId            = UUID.fromString(root["agent_id"]!!.jsonPrimitive.content),
            tenantId           = UUID.fromString(root["tenant_id"]!!.jsonPrimitive.content),
            displayName        = root["display_name"]!!.jsonPrimitive.content,
            jwt                = jwt,
            rabbitmqJwt        = root["rabbitmq_jwt"]?.jsonPrimitive?.content ?: jwt,
            rabbitmqHost       = root["rabbitmq_host"]!!.jsonPrimitive.content,
            rabbitmqPort       = root["rabbitmq_port"]!!.jsonPrimitive.intOrNull
                                    ?: root["rabbitmq_port"]!!.jsonPrimitive.content.toInt(),
            pubsubSubscription = root["pubsub_subscription"]!!.jsonPrimitive.content,
            issuedAt           = Clock.System.now(),
            connectedVia       = ConnectionMethod.API_KEY
        )
    }

    private fun detectPublicIp(): String? = runCatching {
        val c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.ipify.org"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build()
        val resp = c.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) resp.body().trim() else null
    }.getOrNull()

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun firstMac(): String? =
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { !it.isLoopback && it.hardwareAddress != null }
            ?.map { it.hardwareAddress.joinToString(":") { b -> "%02x".format(b) } }
            ?.firstOrNull()
}
