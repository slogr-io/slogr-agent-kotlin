package io.slogr.agent.platform.registration

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.interfaces.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

class ApiKeyRegistrarTest {

    private lateinit var server: HttpServer
    private var serverPort = 0

    private val fakeAgentId  = UUID.randomUUID()
    private val fakeTenantId = UUID.randomUUID()
    private val fakePubsub   = "slogr.agent-commands.$fakeAgentId"

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
        server.createContext("/v1/agents") { ex ->
            ex.sendResponseHeaders(200, fakeResponse.length.toLong())
            ex.responseBody.use { it.write(fakeResponse.toByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() { server.stop(0) }

    @Test
    fun `register stores credential and returns it`() {
        val store = mockk<CredentialStore>(relaxed = true)
        val captured = slot<AgentCredential>()
        every { store.store(capture(captured)) } returns Unit

        val registrar = ApiKeyRegistrar(store, "http://127.0.0.1:$serverPort")
        val cred = runBlocking { registrar.register("sk_live_abc123") }

        assertEquals(fakeAgentId,  cred.agentId)
        assertEquals(fakeTenantId, cred.tenantId)
        assertEquals("test-agent", cred.displayName)
        assertEquals(ConnectionMethod.API_KEY, cred.connectedVia)
        verify { store.store(any()) }
    }

    @Test
    fun `register throws on non-2xx response`() {
        server.stop(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        serverPort = server.address.port
        server.createContext("/v1/agents") { ex ->
            ex.sendResponseHeaders(401, 0)
            ex.responseBody.close()
        }
        server.start()

        val store = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store, "http://127.0.0.1:$serverPort")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { registrar.register("sk_live_abc123") }
        }
    }

    @Test
    fun `register rejects non-sk_live key`() {
        val store = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store, "http://127.0.0.1:$serverPort")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { registrar.register("sk_free_abc123") }
        }
    }

    @Test
    fun `parseCredential maps R2 response fields`() {
        val store     = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store)
        val cred      = registrar.parseCredential(fakeResponse)

        assertEquals(fakeAgentId,  cred.agentId)
        assertEquals(fakeTenantId, cred.tenantId)
        assertEquals("eyJ.test.jwt", cred.jwt)
        assertEquals("eyJ.test.jwt", cred.rabbitmqJwt)  // same JWT used for RabbitMQ
        assertEquals("mq.slogr.io", cred.rabbitmqHost)
        assertEquals(5671,          cred.rabbitmqPort)
        assertEquals(fakePubsub,    cred.pubsubSubscription)
        assertEquals(ConnectionMethod.API_KEY, cred.connectedVia)
    }

    @Test
    fun `parseCredential uses rabbitmq_jwt field when present`() {
        val store = mockk<CredentialStore>(relaxed = true)
        val registrar = ApiKeyRegistrar(store)
        val bodyWithRmqJwt = fakeResponse.trimEnd('}') +
            ","+"\"rabbitmq_jwt\":\"eyJ.rmq.jwt\"}"
        val cred = registrar.parseCredential(bodyWithRmqJwt)
        assertEquals("eyJ.rmq.jwt", cred.rabbitmqJwt)
    }
}
