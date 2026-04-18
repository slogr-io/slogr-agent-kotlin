package io.slogr.agent.platform.registration

import com.sun.net.httpserver.HttpServer
import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.interfaces.CredentialStore
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

class TokenRefresherTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    /** Captures the Authorization and X-Agent-JWT headers of the most recent request. */
    private var lastAuthHeader: String? = null
    private var lastAgentJwtHeader: String? = null
    private var nextResponseBody: String = """{"rabbitmq_jwt":"NEW-JWT-VALUE"}"""
    private var nextResponseCode: Int = 200

    private val cred = AgentCredential(
        agentId            = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        tenantId           = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        displayName        = "test-agent",
        jwt                = "old-agent-jwt",
        rabbitmqJwt        = "old-rabbit-jwt",
        rabbitmqHost       = "rabbit.test",
        rabbitmqPort       = 5672,
        pubsubSubscription = "slogr.agent-commands.11111111-1111-1111-1111-111111111111",
        issuedAt           = Clock.System.now(),
        connectedVia       = ConnectionMethod.API_KEY
    )

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/agents/rabbitmq-token") { exchange ->
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastAgentJwtHeader = exchange.requestHeaders.getFirst("X-Agent-JWT")
            val body = nextResponseBody.toByteArray()
            exchange.sendResponseHeaders(nextResponseCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun teardown() {
        server.stop(0)
        lastAuthHeader = null
        lastAgentJwtHeader = null
        nextResponseBody = """{"rabbitmq_jwt":"NEW-JWT-VALUE"}"""
        nextResponseCode = 200
    }

    private fun store(stored: AgentCredential? = cred): CredentialStore = object : CredentialStore {
        private var c: AgentCredential? = stored
        override fun load(): AgentCredential? = c
        override fun store(credential: AgentCredential) { c = credential }
        override fun delete() { c = null }
        override fun isConnected(): Boolean = c != null
    }

    private fun newRefresher(s: CredentialStore, onRefreshed: suspend (String) -> Unit = {}) =
        TokenRefresher(
            credentialStore = s,
            apiKey          = "sk_live_testkey00000000",
            onRefreshed     = onRefreshed,
            apiBaseUrl      = "http://127.0.0.1:$port"
        )

    @Test
    fun `refresh hits the correct path with both Authorization and X-Agent-JWT headers`() = runBlocking {
        val s = store()
        val refresher = newRefresher(s)

        val newJwt = refresher.refresh()

        assertEquals("NEW-JWT-VALUE", newJwt)
        assertEquals("Bearer sk_live_testkey00000000", lastAuthHeader)
        assertEquals("old-agent-jwt", lastAgentJwtHeader)
    }

    @Test
    fun `refresh parses rabbitmq_jwt key from response body`() = runBlocking {
        // Historical bug: TokenRefresher was parsing `token` not `rabbitmq_jwt` — regression guard.
        nextResponseBody = """{"rabbitmq_jwt":"CORRECT_KEY_VALUE"}"""
        val s = store()
        val newJwt = newRefresher(s).refresh()
        assertEquals("CORRECT_KEY_VALUE", newJwt)
    }

    @Test
    fun `refresh persists the new rabbitmqJwt into the credential store`() = runBlocking {
        val s = store()
        newRefresher(s).refresh()
        assertEquals("NEW-JWT-VALUE", s.load()?.rabbitmqJwt)
    }

    @Test
    fun `refresh calls onRefreshed with the new JWT`() = runBlocking {
        val s = store()
        var captured: String? = null
        newRefresher(s) { j -> captured = j }.refresh()
        assertEquals("NEW-JWT-VALUE", captured)
    }

    @Test
    fun `refresh returns null on non-2xx response`() = runBlocking {
        nextResponseCode = 401
        nextResponseBody = """{"error":"unauthorized"}"""
        val s = store()
        assertNull(newRefresher(s).refresh())
    }

    @Test
    fun `refresh returns null when no stored credential`() = runBlocking {
        val s = store(stored = null)
        assertNull(newRefresher(s).refresh())
    }

    @Test
    fun `refresh returns null when response body missing rabbitmq_jwt key`() = runBlocking {
        nextResponseBody = """{"some_other_key":"value"}"""
        val s = store()
        assertNull(newRefresher(s).refresh())
    }

    @Test
    fun `refresh URL does NOT have -api- prefix`() = runBlocking {
        // Historical bug — refresher hit `/api/v1/...` which doesn't exist on BFF.
        // Server only registers `/v1/agents/rabbitmq-token`; a request to the wrong
        // path returns 404 and the refresh returns null.
        val s = store()
        val result = newRefresher(s).refresh()
        assertNotNull(result, "refresh() must hit /v1/agents/rabbitmq-token (no /api prefix)")
    }
}
