package io.slogr.agent.platform.commands

import io.slogr.agent.platform.pubsub.CommandHandler
import io.slogr.agent.platform.pubsub.CommandResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Handles `upgrade` commands.
 *
 * Security:
 * 1. Download URL must be on `releases.slogr.io` (rejects SSRF attempts).
 * 2. `checksum` must start with `sha256:`.
 * 3. SHA-256 of the downloaded binary must match the checksum.
 *
 * On success: ACKs, then calls [exitProcess](0) — systemd restarts the service.
 *
 * Payload fields:
 * - `download_url` — HTTPS URL on releases.slogr.io
 * - `checksum`     — `sha256:<hex>` of the binary
 */
class UpgradeHandler(
    private val agentId:        UUID,
    private val tenantId:       UUID,
    private val publishResponse: suspend (CommandResponse) -> Unit = {},
    private val doExit:         (Int) -> Unit = { exitProcess(it) }
) : CommandHandler {

    private val log    = LoggerFactory.getLogger(UpgradeHandler::class.java)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override suspend fun handle(commandId: UUID, payload: JsonElement): CommandResponse {
        val obj         = payload.jsonObject
        val downloadUrl = obj["download_url"]?.jsonPrimitive?.content
            ?: return fail(commandId, "missing download_url")
        val checksum    = obj["checksum"]?.jsonPrimitive?.content
            ?: return fail(commandId, "missing checksum")

        // Security: validate URL scheme and host
        val uri = runCatching { URI.create(downloadUrl) }.getOrNull()
            ?: return fail(commandId, "invalid download_url")
        if (uri.scheme != "https") {
            return fail(commandId, "download_url must use https scheme")
        }
        if (uri.host == null || !uri.host.endsWith("releases.slogr.io")) {
            log.warn("Upgrade rejected: URL not on releases.slogr.io — $downloadUrl")
            return fail(commandId, "download_url must be on releases.slogr.io")
        }
        if (!checksum.startsWith("sha256:")) {
            return fail(commandId, "checksum must start with 'sha256:'")
        }
        val expectedHash = checksum.removePrefix("sha256:")
        if (expectedHash.length != 64 || !expectedHash.all { it.isDigit() || it in 'a'..'f' }) {
            return fail(commandId, "checksum must be sha256:<64 lowercase hex chars>")
        }

        // Download to temp file
        val tempFile = withContext(Dispatchers.IO) {
            val tmp = Files.createTempFile("slogr-agent-upgrade-", ".jar")
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
            client.send(request, HttpResponse.BodyHandlers.ofFile(tmp))
            tmp
        }

        // Verify checksum
        val actualHash = withContext(Dispatchers.IO) { sha256Hex(tempFile.toFile().readBytes()) }
        if (actualHash != expectedHash) {
            Files.deleteIfExists(tempFile)
            log.warn("Upgrade checksum mismatch: expected $expectedHash got $actualHash")
            return fail(commandId, "checksum mismatch")
        }

        // Swap binary
        val binaryPath = System.getProperty("slogr.binary.path")
        if (binaryPath != null) {
            withContext(Dispatchers.IO) {
                val current = Paths.get(binaryPath)
                val backup  = current.resolveSibling("slogr-agent.bak")
                Files.move(current, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Files.move(tempFile, current, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                current.toFile().setExecutable(true)
            }
        } else {
            log.warn("slogr.binary.path not set — binary not swapped (dry-run mode)")
            Files.deleteIfExists(tempFile)
        }

        log.info("Upgrade complete. Restarting...")
        val response = CommandResponse(commandId, agentId, tenantId, "acked")
        publishResponse(response)
        doExit(0)
        return response
    }

    private fun fail(commandId: UUID, msg: String): CommandResponse {
        log.warn("Upgrade failed: $msg")
        return CommandResponse(commandId, agentId, tenantId, "failed", error = msg)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
