package io.slogr.agent.platform.asn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Downloads and maintains the ip2asn IPv4 database on disk.
 *
 * 5-tier chain (order is critical):
 * 1. Fresh cache — file + metadata exists and <30 days old
 * 2. Primary download — data.slogr.io (deployment analytics ping)
 * 3. Fallback download — iptoasn.com (public domain)
 * 4. Stale cache — file exists but metadata is old/missing
 * 5. Bundled resource — extract from classpath
 * 6. null
 *
 * On first install there is NO metadata → step 1 fails → agent ALWAYS hits
 * data.slogr.io (step 2). This is the deployment analytics ping. The bundled
 * database must NEVER short-circuit the download attempt on first run.
 */
class AsnDatabaseUpdater(
    private val dataDir: String,
    private val agentId: UUID? = null,
    private val agentVersion: String = "unknown",
    internal val httpGet: (String) -> ByteArray? = ::defaultHttpGet
) {

    private val log  = LoggerFactory.getLogger(AsnDatabaseUpdater::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val dir  = File(dataDir)
    private val tsvFile  = File(dir, TSV_FILENAME)
    private val metaFile = File(dir, METADATA_FILENAME)
    private var job: Job? = null

    /**
     * Ensure the ip2asn database is available on disk. Returns the TSV file
     * path, or null if all tiers fail.
     */
    suspend fun ensureDatabase(): File? {
        dir.mkdirs()

        // Tier 1: fresh cache
        if (isCacheFresh()) {
            log.info("[tier-1] Using cached ip2asn database: {}", tsvFile.absolutePath)
            return tsvFile
        }

        // Tier 2: primary download
        val primaryUrl = buildPrimaryUrl()
        val primaryBytes = tryDownload(primaryUrl, "tier-2/primary")
        if (primaryBytes != null) {
            val written = decompressValidateWrite(primaryBytes, "primary")
            if (written != null) return written
        }

        // Tier 3: fallback download
        val fallbackBytes = tryDownload(FALLBACK_URL, "tier-3/fallback")
        if (fallbackBytes != null) {
            val written = decompressValidateWrite(fallbackBytes, "fallback")
            if (written != null) return written
        }

        // Tier 4: stale cache
        if (tsvFile.exists() && tsvFile.length() > 0) {
            log.warn("[tier-4] Using stale ip2asn database (downloads failed): {}", tsvFile.absolutePath)
            return tsvFile
        }

        // Tier 5: bundled resource
        val extracted = extractBundled()
        if (extracted != null) return extracted

        // Tier 6: nothing available
        log.error("[tier-6] No ip2asn database available. ASN enrichment disabled.")
        return null
    }

    fun startPeriodicRefresh(scope: CoroutineScope, onUpdated: (File) -> Unit) {
        job = scope.launch {
            while (isActive) {
                val result = runCatching { ensureDatabase() }.getOrNull()
                if (result != null && wasNewDownload) {
                    onUpdated(result)
                }
                wasNewDownload = false
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stopPeriodicRefresh() {
        job?.cancel()
    }

    // ── Internal ────────────────────────────────────────────────────────────

    @Volatile
    private var wasNewDownload = false

    private fun isCacheFresh(): Boolean {
        if (!tsvFile.exists() || !metaFile.exists()) return false
        return try {
            val meta = json.parseToJsonElement(metaFile.readText()).jsonObject
            val downloadedAt = Instant.parse(meta["downloaded_at"]!!.jsonPrimitive.content)
            val age = ChronoUnit.DAYS.between(downloadedAt, Instant.now())
            age < REFRESH_INTERVAL_DAYS
        } catch (_: Exception) {
            false
        }
    }

    private fun tryDownload(url: String, tier: String): ByteArray? {
        log.info("[{}] Downloading ip2asn database...", tier)
        return try {
            val bytes = httpGet(url)
            if (bytes == null || bytes.isEmpty()) {
                log.warn("[{}] Download returned empty response", tier)
                null
            } else {
                log.info("[{}] Downloaded {} bytes", tier, bytes.size)
                bytes
            }
        } catch (e: Exception) {
            log.warn("[{}] Download failed: {}", tier, e.message)
            null
        }
    }

    private fun decompressValidateWrite(gzBytes: ByteArray, source: String): File? {
        val decompressed = try {
            GZIPInputStream(ByteArrayInputStream(gzBytes)).readBytes()
        } catch (e: Exception) {
            log.warn("Failed to decompress ip2asn data from {}: {}", source, e.message)
            return null
        }

        if (decompressed.isEmpty()) {
            log.warn("Decompressed ip2asn data from {} is empty", source)
            return null
        }

        // Validate: first line must have 5 tab-separated columns
        val firstLine = decompressed.decodeToString().lineSequence().firstOrNull { it.isNotBlank() }
        if (firstLine == null || firstLine.split('\t').size < 5) {
            log.warn("ip2asn data from {} failed validation: invalid TSV format", source)
            return null
        }

        // Atomic write: write to .tmp then rename
        val tmpFile = File(dir, "$TSV_FILENAME.tmp")
        try {
            tmpFile.writeBytes(decompressed)
            if (!tmpFile.renameTo(tsvFile)) {
                // renameTo can fail on Windows if target exists — delete and retry
                tsvFile.delete()
                if (!tmpFile.renameTo(tsvFile)) {
                    tmpFile.copyTo(tsvFile, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            log.error("Failed to write ip2asn database: {}", e.message)
            tmpFile.delete()
            return null
        }

        writeMetadata(source, decompressed.size.toLong())
        wasNewDownload = true
        log.info("ip2asn database updated from {} ({} bytes)", source, decompressed.size)
        return tsvFile
    }

    private fun writeMetadata(source: String, sizeBytes: Long) {
        val now = Instant.now().toString()
        val content = """{"downloaded_at":"$now","source":"$source","size_bytes":$sizeBytes}"""
        try {
            val tmpMeta = File(dir, "$METADATA_FILENAME.tmp")
            tmpMeta.writeText(content)
            if (!tmpMeta.renameTo(metaFile)) {
                metaFile.delete()
                if (!tmpMeta.renameTo(metaFile)) {
                    tmpMeta.copyTo(metaFile, overwrite = true)
                    tmpMeta.delete()
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to write ip2asn metadata: {}", e.message)
        }
    }

    private fun extractBundled(): File? {
        val resource = javaClass.getResourceAsStream("/ip2asn-v4.tsv.gz") ?: return null
        return try {
            val decompressed = GZIPInputStream(resource).readBytes()
            val tmpFile = File(dir, "$TSV_FILENAME.tmp")
            tmpFile.writeBytes(decompressed)
            if (!tmpFile.renameTo(tsvFile)) {
                tsvFile.delete()
                if (!tmpFile.renameTo(tsvFile)) {
                    tmpFile.copyTo(tsvFile, overwrite = true)
                    tmpFile.delete()
                }
            }
            log.info("[tier-5] Extracted bundled ip2asn database ({} bytes)", decompressed.size)
            tsvFile
        } catch (e: Exception) {
            log.error("[tier-5] Failed to extract bundled ip2asn: {}", e.message)
            null
        }
    }

    private fun buildPrimaryUrl(): String {
        val sb = StringBuilder(PRIMARY_URL)
        if (agentId != null) sb.append("?agent_id=").append(agentId)
        sb.append(if (agentId != null) "&" else "?")
        sb.append("v=").append(agentVersion)
        return sb.toString()
    }

    companion object {
        const val PRIMARY_URL           = "https://data.slogr.io/asn-db"
        const val FALLBACK_URL          = "https://iptoasn.com/data/ip2asn-v4.tsv.gz"
        const val TSV_FILENAME          = "ip2asn-v4.tsv"
        const val METADATA_FILENAME     = "ip2asn-meta.json"
        const val REFRESH_INTERVAL_DAYS = 30
        const val HTTP_TIMEOUT_SECONDS  = 60L
        const val CHECK_INTERVAL_MS     = 24 * 60 * 60 * 1000L

        private val log = LoggerFactory.getLogger(AsnDatabaseUpdater::class.java)

        fun defaultHttpGet(url: String): ByteArray? {
            val safeUrl = url.substringBefore("?")
            return try {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                log.info("HTTP GET {} → {}", safeUrl, response.statusCode())
                if (response.statusCode() == 200) response.body() else null
            } catch (e: Exception) {
                log.warn("HTTP GET {} failed: {}", safeUrl, e.message)
                null
            }
        }
    }
}
