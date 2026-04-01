package io.slogr.agent.platform.registration

import com.sun.net.httpserver.HttpServer
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.interfaces.CredentialStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.slogr.agent.contracts.AgentCredential
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

/**
 * Integration tests for R2 registration flow.
 * BootstrapRegistrar is deleted in R2; all tests use ApiKeyRegistrar and InteractiveRegistrar.
 */
class RegistrationTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    private val fakeAgentId  = UUID.randomUUID()
    private val fakeTenantId = UUID.randomUUID()
    private val fakePubsub   = "slogr.agent-commands.$fakeAgentId"

    /** R2-format response (credential field, flat rabbitmq_host/port). */
    private val fakeResponse = """
        {
          "agent_id":            "$fakeAgentId",
          "tenant_id":           "$fakeTenantId",
          "display_name":        "test-agent",
          "credential":          "eyJ.test.jwt",
          "rabbitmq_host":       "mq.slogr.io",
          "rabbitmq_port":       5671,
          "pubsub_subscription": "$fakePubsub"
        }
    """.trimIndent()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        // R2 endpoint
        server.createContext("/v1/agents") { ex ->
            ex.sendResponseHeaders(200, fakeResponse.length.toLong())
            ex.responseBody.use { it.write(fakeResponse.toByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() { server.stop(0) }

    // ── ApiKeyRegistrar ───────────────────────────────────────────────────────

    @Test
    fun `ApiKeyRegistrar stores credential on success`() {
        val store    = mockk<CredentialStore>(relaxed = true)
        val captured = slot<AgentCredential>()
        every { store.store(capture(captured)) } returns Unit

        val registrar = ApiKeyRegistrar(store, "http://127.0.0.1:$serverPort")
        val cred = kotlinx.coroutines.runBlocking { registrar.register("sk_live_abc123") }

        assertEquals(fakeAgentId,  cred.agentId)
        assertEquals(fakeTenantId, cred.tenantId)
        assertEquals("test-agent", cred.displayName)
        assertEquals(ConnectionMethod.API_KEY, cred.connectedVia)
        verify { store.store(any()) }
    }

    @Test
    fun `ApiKeyRegistrar fails on non-2xx response`() {
        server.stop(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/v1/agents") { ex ->
            ex.sendResponseHeaders(401, 0)
            ex.responseBody.close()
        }
        server.start()

        val store     = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store, "http://127.0.0.1:$serverPort")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { registrar.register("sk_live_abc123") }
        }
    }

    // ── InteractiveRegistrar ─────────────────────────────────────────────────

    @Test
    fun `InteractiveRegistrar stores credential with API_KEY method`() {
        val store    = mockk<CredentialStore>(relaxed = true)
        val captured = slot<AgentCredential>()
        every { store.store(capture(captured)) } returns Unit

        val registrar = InteractiveRegistrar(store, "http://127.0.0.1:$serverPort")
        val cred = kotlinx.coroutines.runBlocking { registrar.register("sk_live_abc123") }

        assertEquals(fakeAgentId,  cred.agentId)
        assertEquals(ConnectionMethod.API_KEY, cred.connectedVia)
        verify { store.store(any()) }
    }

    // ── parseCredential ───────────────────────────────────────────────────────

    @Test
    fun `parseCredential handles optional gcp_service_account_key`() {
        val store     = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store)
        val withKey   = fakeResponse.trimEnd('}') +
            ""","gcp_service_account_key":"base64abc"}"""
        val cred = registrar.parseCredential(withKey)
        // gcp_service_account_key is no longer in AgentCredential R2 — ignored via ignoreUnknownKeys
        assertNotNull(cred)
    }

    @Test
    fun `parseCredential without optional fields uses defaults`() {
        val store     = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store)
        val cred = registrar.parseCredential(fakeResponse)
        assertEquals("eyJ.test.jwt", cred.jwt)
        assertEquals("eyJ.test.jwt", cred.rabbitmqJwt)  // falls back to credential field
        assertNull(cred.gcpServiceAccountKey)
    }
}
