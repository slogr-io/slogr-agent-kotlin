package io.slogr.desktop.core.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class DiagnosticResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/**
 * Runs basic connectivity diagnostics to help users troubleshoot issues.
 */
object DiagnosticsRunner {

    suspend fun runAll(): List<DiagnosticResult> = withContext(Dispatchers.IO) {
        listOf(
            checkDns(),
            checkHttps(),
            checkReflectorPort(),
        )
    }

    private fun checkDns(): DiagnosticResult {
        return try {
            val addr = InetAddress.getByName("api.slogr.io")
            DiagnosticResult("DNS Resolution", true, "api.slogr.io → ${addr.hostAddress}")
        } catch (e: Exception) {
            DiagnosticResult("DNS Resolution", false, "Failed: ${e.message}")
        }
    }

    private fun checkHttps(): DiagnosticResult {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.slogr.io/"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            DiagnosticResult("HTTPS Connectivity", true, "Status ${response.statusCode()}")
        } catch (e: Exception) {
            DiagnosticResult("HTTPS Connectivity", false, "Failed: ${e.message}")
        }
    }

    private fun checkReflectorPort(): DiagnosticResult {
        return try {
            // Try to reach localhost:862 (local reflector for dev)
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", 862), 3000)
            socket.close()
            DiagnosticResult("TWAMP Port (862)", true, "127.0.0.1:862 reachable")
        } catch (e: Exception) {
            DiagnosticResult(
                "TWAMP Port (862)", false,
                "127.0.0.1:862 unreachable (expected unless local reflector running)",
            )
        }
    }
}
