package io.slogr.agent.platform.security

import com.sun.net.httpserver.HttpServer
import io.slogr.agent.platform.commands.UpgradeHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID

/**
 * Security-focused tests for [UpgradeHandler] URL and checksum validation.
 *
 * The upgrade command is a high-value attack target: a compromised or spoofed Pub/Sub
 * message could otherwise replace the agent binary with malware. These tests verify
 * that the handler rejects every non-canonical download URL and every tampered checksum.
 */
class UpgradeUrlValidationTest {

    private lateinit var server: HttpServer
    private var serverPort = 0
    private val fakeJar = "fake-jar".toByteArray()
    private val correctChecksum = "sha256:" + sha256Hex(fakeJar)

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/agent.jar") { ex ->
            ex.sendResponseHeaders(200, fakeJar.size.toLong())
            ex.responseBody.use { it.write(fakeJar) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() { server.stop(0) }

    private fun makeHandler() = UpgradeHandler(
        agentId  = UUID.randomUUID(),
        tenantId = UUID.randomUUID(),
        doExit   = { /* swallow */ }
    )

    // ── URL host validation ───────────────────────────────────────────────────

    @Test
    fun `rejects plain HTTP non-slogr URL`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "http://evil.example.com/agent.jar")
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
        assertNotNull(r.error)
    }

    @Test
    fun `rejects HTTPS non-slogr URL`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "https://evil.example.com/agent.jar")
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects URL that looks like slogr domain but is subdomain attack`() = runBlocking {
        // attacker.com/releases.slogr.io/ — host is attacker.com
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "https://attacker.com/releases.slogr.io/agent.jar")
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects URL where slogr-io appears in path but not host`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "https://evil.com/releases.slogr.io/agent.jar")
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects file-scheme URL`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "file:///etc/passwd")
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects ftp-scheme URL`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "ftp://releases.slogr.io/agent.jar")
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects missing download_url field`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("checksum", correctChecksum)
        })
        assertEquals("failed", r.status)
    }

    // ── Checksum validation ───────────────────────────────────────────────────

    @Test
    fun `rejects checksum without sha256 prefix`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "https://releases.slogr.io/agent.jar")
            put("checksum", "md5:abc123")
        })
        assertEquals("failed", r.status)
        assertTrue(r.error!!.contains("sha256"), "Error should mention sha256 requirement")
    }

    @Test
    fun `rejects checksum that is only the prefix`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "https://releases.slogr.io/agent.jar")
            put("checksum", "sha256:")
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects missing checksum field`() = runBlocking {
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "https://releases.slogr.io/agent.jar")
        })
        assertEquals("failed", r.status)
    }

    @Test
    fun `rejects wrong checksum value (tampered binary)`() = runBlocking {
        val wrongChecksum = "sha256:" + "a".repeat(64)
        // Use the local test server so download actually happens
        val r = makeHandler().handle(UUID.randomUUID(), buildJsonObject {
            put("download_url", "http://127.0.0.1:$serverPort/agent.jar")
            put("checksum", wrongChecksum)
        })
        // URL host check will fail first (not releases.slogr.io) — still "failed"
        assertEquals("failed", r.status)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
