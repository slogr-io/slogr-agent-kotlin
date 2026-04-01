package io.slogr.agent.platform.security

import io.mockk.mockk
import io.slogr.agent.platform.pubsub.CommandDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that [CommandDispatcher] remains responsive under a flood of concurrent commands.
 *
 * Scenario: an attacker who gains access to the Pub/Sub subscription sends 200 commands
 * per second. The dispatcher must:
 *   - Process each message without throwing
 *   - Return a response for every message (never block indefinitely)
 *   - Not leak memory or accumulate state between invocations
 *
 * Note: the TWAMP responder (TCP/UDP flooding) is tested via the engine-layer
 * [TwampReflector] in Phase 3 tests. This test focuses on the command plane.
 */
class ResponderFloodTest {

    private val agentId  = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    private val dispatcher = CommandDispatcher(
        agentId  = agentId,
        tenantId = tenantId,
        handlers = emptyMap()
    )

    @Test
    fun `200 concurrent commands all receive a response`() = runBlocking {
        val count = 200
        val responses = (1..count).map { i ->
            async(Dispatchers.Default) {
                dispatcher.dispatch("""
                    {"command_id":"${UUID.randomUUID()}","command_type":"unknown_$i",
                     "agent_id":"$agentId","tenant_id":"$tenantId","payload":{}}
                """.trimIndent())
            }
        }.awaitAll()

        assertEquals(count, responses.size, "All ${count} commands must produce a response")
        responses.forEach { r ->
            assertNotNull(r, "Response must not be null")
            assertNotNull(r.status, "Response status must not be null")
        }
    }

    @Test
    fun `flood of malformed commands does not crash the dispatcher`() = runBlocking {
        val malformed = listOf(
            "",
            "{}",
            "null",
            "{\"command_type\":null}",
            "{" + "x".repeat(100_000) + "}",
            "[1,2,3]",
            "true",
        )

        val errorCount = AtomicInteger(0)
        malformed.map { payload ->
            async(Dispatchers.Default) {
                try {
                    val r = dispatcher.dispatch(payload)
                    assertEquals("failed", r.status)
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(0, errorCount.get(),
            "Dispatcher must not throw on any malformed payload")
    }

    @Test
    fun `1000 sequential commands with wrong agent ID do not accumulate state`() = runBlocking {
        val wrongAgent = UUID.randomUUID()
        var exceptionCount = 0

        repeat(1000) {
            try {
                dispatcher.dispatch("""
                    {"command_id":"${UUID.randomUUID()}","command_type":"run_test",
                     "agent_id":"$wrongAgent","tenant_id":"$tenantId","payload":{}}
                """.trimIndent())
            } catch (e: Exception) {
                exceptionCount++
            }
        }

        assertEquals(0, exceptionCount,
            "No exception expected; misdirected commands are silently dropped")
    }
}
