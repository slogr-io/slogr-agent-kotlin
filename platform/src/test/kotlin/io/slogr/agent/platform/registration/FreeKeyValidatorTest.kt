package io.slogr.agent.platform.registration

import com.sun.net.httpserver.HttpServer
import io.slogr.agent.platform.config.AgentState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant

class FreeKeyValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private var serverPort = 0

    private val fakeTenantId = "aaaaaaaa-0000-0000-0000-000000000000"
    private val okResponse   = """{"valid":true,"key_type":"free","tenant_id":"$fakeTenantId"}"""

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/v1/keys/validate") { ex ->
            ex.sendResponseHeaders(200, okResponse.length.toLong())
            ex.responseBody.use { it.write(okResponse.toByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() { server.stop(0) }

    private fun cache() = KeyValidationCache(tempDir.resolve("key_validation.json"))
    private fun validator(port: Int = serverPort) =
        FreeKeyValidator(cache(), "http://127.0.0.1:$port")

    @Test
    fun `validates key via API and returns REGISTERED`() {
        val result = runBlocking { validator().validate("sk_free_abc123") }
        assertEquals(AgentState.REGISTERED, result.state)
        assertEquals(fakeTenantId, result.tenantId)
    }

    @Test
    fun `uses cached result when valid and not expired`() {
        val c = cache()
        c.write(KeyValidationCache.Entry(
            valid       = true,
            keyType     = "free",
            tenantId    = "cached-tenant",
            validatedAt = Instant.now().toString()
        ))
        // Use wrong port so that any actual network call would fail
        val result = runBlocking { FreeKeyValidator(c, "http://127.0.0.1:1").validate("sk_free_abc123") }
        assertEquals(AgentState.REGISTERED, result.state)
        assertEquals("cached-tenant", result.tenantId)
    }

    @Test
    fun `returns ANONYMOUS on 401 response`() {
        server.stop(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/v1/keys/validate") { ex ->
            ex.sendResponseHeaders(401, 0)
            ex.responseBody.close()
        }
        server.start()

        val result = runBlocking { validator().validate("sk_free_abc123") }
        assertEquals(AgentState.ANONYMOUS, result.state)
        assertNull(result.tenantId)
    }

    @Test
    fun `returns REGISTERED on network error (air-gap support)`() {
        // Port 1 is not listening — connection refused
        val result = runBlocking {
            FreeKeyValidator(cache(), "http://127.0.0.1:1").validate("sk_free_abc123")
        }
        assertEquals(AgentState.REGISTERED, result.state)
    }

    @Test
    fun `rejects non-sk_free key`() {
        val v = validator()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { v.validate("sk_live_abc123") }
        }
    }
}
