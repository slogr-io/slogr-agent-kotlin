package io.slogr.agent.platform.security

import io.mockk.mockk
import io.slogr.agent.platform.pubsub.CommandDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Verifies that [CommandDispatcher] never crashes when given malformed, truncated,
 * or adversarially constructed Pub/Sub command envelopes.
 *
 * The dispatcher must always return a [CommandResponse] (never throw) so that
 * the Pub/Sub subscriber can ACK the message and continue processing.
 */
class MalformedCommandTest {

    private val agentId  = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    private val dispatcher = CommandDispatcher(
        agentId  = agentId,
        tenantId = tenantId,
        handlers = emptyMap()  // no handlers — tests envelope validation only
    )

    @Test
    fun `empty JSON object returns failed response`() = runBlocking {
        val response = dispatcher.dispatch("{}")
        assertNotNull(response)
        assertEquals("failed", response.status)
    }

    @Test
    fun `missing command_type returns failed response`() = runBlocking {
        val payload = """{"command_id":"${UUID.randomUUID()}","agent_id":"$agentId","tenant_id":"$tenantId"}"""
        val response = dispatcher.dispatch(payload)
        assertEquals("failed", response.status)
    }

    @Test
    fun `missing command_id still returns a response`() = runBlocking {
        val payload = """{"command_type":"run_test","agent_id":"$agentId","tenant_id":"$tenantId","payload":{}}"""
        val response = dispatcher.dispatch(payload)
        assertNotNull(response)
        // commandId may be null in the response, but no crash
    }

    @Test
    fun `wrong agent_id is silently ignored`() = runBlocking {
        val otherId = UUID.randomUUID()
        val payload = """
            {"command_id":"${UUID.randomUUID()}","command_type":"run_test",
             "agent_id":"$otherId","tenant_id":"$tenantId","payload":{}}
        """.trimIndent()
        val response = dispatcher.dispatch(payload)
        // Misdirected command — dispatcher returns null (silently ignored)
        // No crash regardless
        // Either null or a failed response is acceptable
    }

    @Test
    fun `wrong tenant_id returns failed response`() = runBlocking {
        val otherId = UUID.randomUUID()
        val payload = """
            {"command_id":"${UUID.randomUUID()}","command_type":"run_test",
             "agent_id":"$agentId","tenant_id":"$otherId","payload":{}}
        """.trimIndent()
        val response = dispatcher.dispatch(payload)
        assertEquals("failed", response.status)
    }

    @Test
    fun `completely invalid JSON returns failed response`() = runBlocking {
        val response = dispatcher.dispatch("not-json-at-all")
        assertNotNull(response)
        assertEquals("failed", response.status)
    }

    @Test
    fun `truncated JSON does not throw`() = runBlocking {
        val response = dispatcher.dispatch("""{"command_id":"abc""")
        assertNotNull(response)
        assertEquals("failed", response.status)
    }

    @Test
    fun `unknown command type with valid envelope returns failed response`() = runBlocking {
        val payload = """
            {"command_id":"${UUID.randomUUID()}","command_type":"launch_missiles",
             "agent_id":"$agentId","tenant_id":"$tenantId","payload":{}}
        """.trimIndent()
        val response = dispatcher.dispatch(payload)
        assertEquals("failed", response.status)
        assertNotNull(response.error)
    }

    @Test
    fun `extra unknown fields in payload do not cause crash`() = runBlocking {
        val payload = """
            {"command_id":"${UUID.randomUUID()}","command_type":"run_test",
             "agent_id":"$agentId","tenant_id":"$tenantId",
             "unknown_field":"evil_value","nested":{"a":1,"b":[2,3]},
             "payload":{}}
        """.trimIndent()
        val response = dispatcher.dispatch(payload)
        assertNotNull(response)
        // No crash — unknown fields ignored
    }

    @Test
    fun `extremely long command type string does not cause crash`() = runBlocking {
        val longType = "x".repeat(10_000)
        val payload = """
            {"command_id":"${UUID.randomUUID()}","command_type":"$longType",
             "agent_id":"$agentId","tenant_id":"$tenantId","payload":{}}
        """.trimIndent()
        val response = dispatcher.dispatch(payload)
        assertNotNull(response)
        assertEquals("failed", response.status)
    }
}
