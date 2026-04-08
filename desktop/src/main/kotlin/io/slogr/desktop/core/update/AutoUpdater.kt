package io.slogr.desktop.core.update

import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Silent auto-updater. Checks slogr.io/desktop/update.json on startup and every 24h.
 * If a newer version is found, downloads the MSI to the data directory.
 * On next app launch, if a pending MSI exists, it launches the installer and exits.
 *
 * Fails silently on any error — network down, 404, bad JSON, download failure.
 */
class AutoUpdater(private val dataDir: Path) {

    private val log = LoggerFactory.getLogger(AutoUpdater::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    companion object {
        const val CURRENT_VERSION = "1.1.0"
        const val UPDATE_URL = "https://slogr.io/desktop/update.json"
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000 // 24 hours
        private const val PENDING_MSI = "slogr-update.msi"
    }

    @Serializable
    data class UpdateInfo(
        val version: String,
        @SerialName("download_url") val downloadUrl: String,
        @SerialName("release_notes") val releaseNotes: String = "",
    )

    /**
     * Call on app startup. If a pending MSI was downloaded in a previous session,
     * launch it and return true (caller should exit). Otherwise returns false.
     */
    fun applyPendingUpdate(): Boolean {
        val msi = dataDir.resolve(PENDING_MSI).toFile()
        if (msi.exists() && msi.length() > 100_000) { // sanity check: >100KB
            try {
                log.info("Applying pending update: {}", msi.absolutePath)
                ProcessBuilder("msiexec", "/i", msi.absolutePath, "/qn").start()
                return true // caller should exitApplication()
            } catch (e: Exception) {
                log.warn("Failed to launch update installer: {}", e.message)
                msi.delete()
            }
        }
        return false
    }

    /** Start background check loop. */
    fun start() {
        scope.launch {
            // First check after 60 seconds (let the app settle)
            delay(60_000)
            checkAndDownload()
            // Then every 24 hours
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkAndDownload()
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun checkAndDownload() {
        try {
            val info = fetchUpdateInfo() ?: return
            if (!isNewer(info.version, CURRENT_VERSION)) return
            log.info("Update available: {} -> {}", CURRENT_VERSION, info.version)
            downloadMsi(info.downloadUrl)
        } catch (_: Exception) {
            // Silent fail — network issues, bad URL, anything
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(UPDATE_URL))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            json.decodeFromString<UpdateInfo>(response.body())
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadMsi(url: String) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(300)) // 5 min for large MSI
                .GET()
                .build()
            val dest = dataDir.resolve(PENDING_MSI)
            client.send(request, HttpResponse.BodyHandlers.ofFile(dest))
            log.info("Update downloaded to {}", dest)
        } catch (e: Exception) {
            log.debug("Update download failed: {}", e.message)
        }
    }

    /** Simple semver comparison: "1.2.0" > "1.1.0" */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }
}
