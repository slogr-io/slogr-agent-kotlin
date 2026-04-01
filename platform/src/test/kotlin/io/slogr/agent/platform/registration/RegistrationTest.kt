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

class RegistrationTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    private val fakeAgentId  = UUID.randomUUID()
    private val fakeTenantId = UUID.randomUUID()
    private val fakePubsub   = "slogr.agent-commands.$fakeAgentId"

    private val fakeResponse = """
        {
          "agent_id":            "$fakeAgentId",
          "tenant_id":           "$fakeTenantId",
          "display_name":        "test-agent",
          "jwt":                 "eyJ.test.jwt",
          "rabbitmq_jwt":        "eyJ.rmq.jwt",
          "rabbitmq":            {"host":"mq.slogr.io","port":5671},
          "pubsub_subscription": "$fakePubsub"
        }
    """.trimIndent()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/api/v1/agents/register") { ex ->
            ex.sendResponseHeaders(200, fakeResponse.length.toLong())
            ex.responseBody.use { it.write(fakeResponse.toByteArray()) }
        }
        server.createContext("/api/v1/agents/connect") { ex ->
            ex.sendResponseHeaders(200, fakeResponse.length.toLong())
            ex.responseBody.use { it.write(fakeResponse.toByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() { server.stop(0) }

    // ── BootstrapRegistrar ───────────────────────────────────────────────────

    @Test
    fun `bootstrap registration stores credential`() {
        val store = mockk<CredentialStore>(relaxed = true)
        val stored = slot<AgentCredential>()
        every { store.store(capture(stored)) } returns Unit

        val registrar = BootstrapRegistrar(store, "http://127.0.0.1:$serverPort")
        val cred = kotlinx.coroutines.runBlocking { registrar.register("tok_test") }

        assertEquals(fakeAgentId,  cred.agentId)
        assertEquals(fakeTenantId, cred.tenantId)
        assertEquals("test-agent", cred.displayName)
        assertEquals(ConnectionMethod.BOOTSTRAP_TOKEN, cred.connectedVia)
        verify { store.store(any()) }
    }

    @Test
    fun `bootstrap registration fails on non-2xx response`() {
        // Stop the current server and create one that returns 401
        server.stop(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/api/v1/agents/register") { ex ->
            ex.sendResponseHeaders(401, 0)
            ex.responseBody.close()
        }
        server.start()

        val store     = mockk<CredentialStore>(relaxed = true)
        val registrar = BootstrapRegistrar(store, "http://127.0.0.1:$serverPort")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { registrar.register("tok_test") }
        }
    }

    // ── InteractiveRegistrar ─────────────────────────────────────────────────

    @Test
    fun `interactive registration stores credential with API_KEY method`() {
        val store = mockk<CredentialStore>(relaxed = true)
        val stored = slot<AgentCredential>()
        every { store.store(capture(stored)) } returns Unit

        val registrar = InteractiveRegistrar(store, "http://127.0.0.1:$serverPort")
        val cred = kotlinx.coroutines.runBlocking { registrar.register("sk_live_abc") }

        assertEquals(fakeAgentId,  cred.agentId)
        assertEquals(ConnectionMethod.API_KEY, cred.connectedVia)
        verify { store.store(any()) }
    }

    // ── parseCredential ───────────────────────────────────────────────────────

    @Test
    fun `parseCredential handles optional gcp_service_account_key`() {
        val store      = mockk<CredentialStore>(relaxed = true)
        val registrar  = BootstrapRegistrar(store)
        val withKey    = fakeResponse.trimIndent().replace("}", ""","gcp_service_account_key":"base64abc"}""")
        val cred = registrar.parseCredential(withKey, ConnectionMethod.BOOTSTRAP_TOKEN)
        assertEquals("base64abc", cred.gcpServiceAccountKey)
    }

    @Test
    fun `parseCredential without gcp key leaves field null`() {
        val store     = mockk<CredentialStore>(relaxed = true)
        val registrar = BootstrapRegistrar(store)
        val cred = registrar.parseCredential(fakeResponse, ConnectionMethod.BOOTSTRAP_TOKEN)
        assertNull(cred.gcpServiceAccountKey)
    }
}
