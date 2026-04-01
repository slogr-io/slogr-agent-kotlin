package io.slogr.agent.platform.commands

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID

class UpgradeHandlerTest {

    private lateinit var server: HttpServer
    private var serverPort = 0
    private val agentId  = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    private val fakeJarBytes = "fake-jar-content".toByteArray()
    private val correctChecksum = "sha256:" + sha256Hex(fakeJarBytes)
    private val wrongChecksum   = "sha256:" + "a".repeat(64)

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/agent.jar") { ex ->
            ex.sendResponseHeaders(200, fakeJarBytes.size.toLong())
            ex.responseBody.use { it.write(fakeJarBytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() { server.stop(0) }

    private fun makeHandler(exitCalled: MutableList<Int> = mutableListOf()) = UpgradeHandler(
        agentId  = agentId,
        tenantId = tenantId,
        doExit   = { exitCalled += it }
    )

    // ── URL validation ────────────────────────────────────────────────────────

    @Test
    fun `rejects URL not on releases slogr io`() = runBlocking {
        val handler = makeHandler()
        val payload = buildJsonObject {
            put("download_url", "http://evil.example.com/malware.jar")
            put("checksum",     correctChecksum)
        }
        val response = handler.handle(UUID.randomUUID(), payload)
        assertEquals("failed", response.status)
        assertTrue(response.error!!.contains("releases.slogr.io"))
    }

    @Test
    fun `rejects missing download_url`() = runBlocking {
        val handler = makeHandler()
        val payload = buildJsonObject { put("checksum", correctChecksum) }
        val response = handler.handle(UUID.randomUUID(), payload)
        assertEquals("failed", response.status)
    }

    @Test
    fun `rejects checksum without sha256 prefix`() = runBlocking {
        val handler = makeHandler()
        val payload = buildJsonObject {
            put("download_url", "https://releases.slogr.io/agent.jar")
            put("checksum",     "md5:abc123")
        }
        val response = handler.handle(UUID.randomUUID(), payload)
        assertEquals("failed", response.status)
    }

    // ── Checksum verification ─────────────────────────────────────────────────

    @Test
    fun `rejects checksum without sha256 prefix (non-sha256 format)`() = runBlocking {
        val handler = makeHandler()
        val payload = buildJsonObject {
            put("download_url", "https://releases.slogr.io/agent.jar")
            put("checksum",     wrongChecksum.replace("sha256:", "md5:"))
        }
        val response = handler.handle(UUID.randomUUID(), payload)
        assertEquals("failed", response.status)
        assertTrue(response.error!!.contains("sha256"))
    }

    // ── CommandDispatcher integration ─────────────────────────────────────────

    @Test
    fun `registered under upgrade key in dispatcher`() {
        val exitCalled = mutableListOf<Int>()
        val handler    = makeHandler(exitCalled)
        val dispatcher = io.slogr.agent.platform.pubsub.CommandDispatcher(
            agentId  = agentId,
            tenantId = tenantId,
            handlers = mapOf("upgrade" to handler)
        )
        val payload = """
            {"command_id":"${UUID.randomUUID()}","command_type":"upgrade",
             "agent_id":"$agentId","tenant_id":"$tenantId",
             "payload":{"download_url":"http://evil.com/x","checksum":"sha256:abc"}}
        """.trimIndent()
        val response = runBlocking { dispatcher.dispatch(payload) }
        // URL rejected → failed
        assertEquals("failed", response.status)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
