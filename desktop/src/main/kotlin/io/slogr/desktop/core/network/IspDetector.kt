package io.slogr.desktop.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Detects the user's public IP and ISP/ASN via lightweight HTTP APIs.
 *
 * Step 1: Detect public IP via ipify/ifconfig.me/checkip.amazonaws.com (plain text).
 * Step 2: Look up ISP via ipinfo.io (returns "AS17557 Pakistan Telecommunication Company Limited").
 *
 * Results cached for 24 hours. Failures are silent — ISP display simply shows nothing.
 */
class IspDetector {

    private val log = LoggerFactory.getLogger(IspDetector::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    companion object {
        private val IP_SOURCES = listOf(
            "https://api.ipify.org?format=text",
            "https://ifconfig.me/ip",
            "https://checkip.amazonaws.com",
        )
        private const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    private val _ispInfo = MutableStateFlow<IspInfo?>(null)
    val ispInfo: StateFlow<IspInfo?> = _ispInfo.asStateFlow()

    fun start() {
        scope.launch {
            detect()
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                detect()
            }
        }
    }

    fun stop() { scope.cancel() }

    internal fun detect() {
        try {
            val ip = detectPublicIp() ?: return
            val info = lookupAsn(ip) ?: return
            _ispInfo.value = info
            log.info("ISP detected: {} (AS{})", info.ispName, info.asn)
        } catch (_: Exception) {
            // Silent fail — ISP display just won't show
        }
    }

    internal fun detectPublicIp(): String? {
        for (source in IP_SOURCES) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(source))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val ip = response.body().trim()
                    if (ip.isNotBlank() && ip.length <= 45) return ip
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    internal fun lookupAsn(ip: String): IspInfo? {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://ipinfo.io/$ip/org"))
                .timeout(Duration.ofSeconds(5))
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            return parseOrgLine(response.body().trim(), ip)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Parse ipinfo.io /org response: "AS17557 Pakistan Telecommunication Company Limited"
     */
    internal fun parseOrgLine(orgLine: String, ip: String): IspInfo? {
        if (orgLine.isBlank() || !orgLine.startsWith("AS")) return null
        val parts = orgLine.split(" ", limit = 2)
        if (parts.size < 2) return null
        val asn = parts[0].removePrefix("AS").toIntOrNull() ?: return null
        val name = parts[1].trim()
        if (name.isBlank()) return null
        return IspInfo(ispName = name, asn = asn, publicIp = ip)
    }
}
