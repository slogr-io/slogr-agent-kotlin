package io.slogr.desktop.core.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Update checker. Checks slogr.io/desktop/update.json on startup and every 24h.
 * If a newer version is found, sets [updateAvailable] to true with the download URL.
 * The user must click to open the browser and download manually.
 *
 * SECURITY: Never downloads or executes anything automatically. No MSI is written
 * to disk. The agent only checks a JSON endpoint and shows a notification.
 * The user downloads from slogr.io via their browser (HTTPS, verified domain).
 */
class AutoUpdater {

    private val log = LoggerFactory.getLogger(AutoUpdater::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    companion object {
        const val CURRENT_VERSION = "1.1.0"
        val UPDATE_URLS = listOf(
            "https://api.slogr.io/v1/desktop/latest",
            "https://slogr.io/desktop/update.json",
        )
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val DISMISS_DURATION_MS = 24L * 60 * 60 * 1000
    }

    @Serializable
    data class UpdateInfo(
        val version: String,
        @SerialName("download_url_windows") val downloadUrlWindows: String? = null,
        @SerialName("download_url_macos") val downloadUrlMacos: String? = null,
        @SerialName("download_url") val downloadUrl: String? = null,
        @SerialName("release_notes") val releaseNotes: String? = null,
        val required: Boolean = false,
    ) {
        /** Platform-appropriate download URL */
        val platformDownloadUrl: String? get() {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("mac") -> downloadUrlMacos ?: downloadUrl
                else -> downloadUrlWindows ?: downloadUrl
            }
        }
    }

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    fun start() {
        scope.launch {
            delay(60_000) // wait 60s after startup
            check()
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                check()
            }
        }
    }

    fun stop() { scope.cancel() }

    /** Open the download URL in the system browser. */
    fun openDownloadPage() {
        val info = _updateAvailable.value ?: return
        val url = info.platformDownloadUrl ?: return
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (e: Exception) {
            log.warn("Failed to open browser: {}", e.message)
        }
    }

    /** Dismiss the update banner for 24 hours. */
    fun dismiss() {
        _updateAvailable.value = null
        dismissedUntil = System.currentTimeMillis() + DISMISS_DURATION_MS
    }

    private var dismissedUntil: Long = 0L

    private fun check() {
        // Skip check if dismissed (unless required updates override)
        if (System.currentTimeMillis() < dismissedUntil && _updateAvailable.value?.required != true) return
        for (url in UPDATE_URLS) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) continue
                val info = json.decodeFromString<UpdateInfo>(response.body())
                if (isNewer(info.version, CURRENT_VERSION)) {
                    log.info("Update available: {} -> {}", CURRENT_VERSION, info.version)
                    _updateAvailable.value = info
                }
                return // success — don't try other URLs
            } catch (_: Exception) {
                continue
            }
        }
    }

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
