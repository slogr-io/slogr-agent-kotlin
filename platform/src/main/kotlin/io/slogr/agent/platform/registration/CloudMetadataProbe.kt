package io.slogr.agent.platform.registration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Detects the cloud provider and region by probing IMDS endpoints in parallel.
 *
 * All three probes (AWS, GCP, Azure) run concurrently with a 2-second timeout each.
 * The first successful probe wins. Falls back to "agent"/"unknown" if none respond.
 */
object CloudMetadataProbe {

    private val log = LoggerFactory.getLogger(CloudMetadataProbe::class.java)

    data class CloudMetadata(
        val cloud:      String,     // "aws", "gcp", "azure", "agent"
        val region:     String,     // "us-east-1", "us-central1", "eastus", "unknown"
        val instanceId: String      // instance ID or hostname
    )

    suspend fun detect(): CloudMetadata = withContext(Dispatchers.IO) {
        val aws   = async { probeAws() }
        val gcp   = async { probeGcp() }
        val azure = async { probeAzure() }

        aws.await()   ?:
        gcp.await()   ?:
        azure.await() ?:
        fallback()
    }

    // ── Probes ────────────────────────────────────────────────────────────────

    private suspend fun probeAws(): CloudMetadata? = withTimeoutOrNull(2_000) {
        runCatching {
            // AWS IMDSv1 — region is embedded in the AZ (e.g. "us-east-1a" → "us-east-1")
            val az  = httpGet("http://169.254.169.254/latest/meta-data/placement/availability-zone")
                          ?: return@runCatching null
            val id  = httpGet("http://169.254.169.254/latest/meta-data/instance-id") ?: "unknown"
            val region = az.dropLast(1)    // strip trailing AZ letter
            CloudMetadata("aws", region, id)
        }.getOrNull()
    }

    private suspend fun probeGcp(): CloudMetadata? = withTimeoutOrNull(2_000) {
        runCatching {
            // GCP metadata — zone is "projects/{project}/zones/{zone}"
            val zone = httpGet(
                "http://metadata.google.internal/computeMetadata/v1/instance/zone",
                headers = mapOf("Metadata-Flavor" to "Google")
            ) ?: return@runCatching null
            val region = zone.substringAfterLast('/').dropLast(2)   // "us-central1-a" → "us-central1"
            val id = httpGet(
                "http://metadata.google.internal/computeMetadata/v1/instance/id",
                headers = mapOf("Metadata-Flavor" to "Google")
            ) ?: "unknown"
            CloudMetadata("gcp", region, id)
        }.getOrNull()
    }

    private suspend fun probeAzure(): CloudMetadata? = withTimeoutOrNull(2_000) {
        runCatching {
            // Azure IMDS — returns JSON; parse region and instanceId
            val body = httpGet(
                "http://169.254.169.254/metadata/instance?api-version=2021-02-01",
                headers = mapOf("Metadata" to "true")
            ) ?: return@runCatching null
            val region = extractJsonField(body, "location") ?: "unknown"
            val id     = extractJsonField(body, "vmId") ?: "unknown"
            CloudMetadata("azure", region, id)
        }.getOrNull()
    }

    private fun fallback(): CloudMetadata {
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
        return CloudMetadata("agent", "unknown", hostname)
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(2))
            .GET()
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        val response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) response.body() else null
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = Regex(""""$field"\s*:\s*"([^"]+)"""")
        return pattern.find(json)?.groupValues?.get(1)
    }
}
