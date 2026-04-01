package io.slogr.agent.platform.rabbitmq

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DeduplicationCacheTest {

    private val sessionId = UUID.randomUUID()
    private val cache     = DeduplicationCache()

    @Test
    fun `first call for a session is never a duplicate`() {
        assertFalse(cache.isDuplicate(sessionId, """{"foo":"bar"}"""))
    }

    @Test
    fun `same JSON for same session is a duplicate`() {
        val json = """{"rtt":12}"""
        cache.isDuplicate(sessionId, json)
        assertTrue(cache.isDuplicate(sessionId, json))
    }

    @Test
    fun `different JSON for same session is not a duplicate`() {
        cache.isDuplicate(sessionId, """{"rtt":12}""")
        assertFalse(cache.isDuplicate(sessionId, """{"rtt":15}"""))
    }

    @Test
    fun `different sessions are independent`() {
        val s1 = UUID.randomUUID()
        val s2 = UUID.randomUUID()
        val json = """{"rtt":12}"""
        cache.isDuplicate(s1, json)
        assertFalse(cache.isDuplicate(s2, json))
    }

    @Test
    fun `evict clears stored hash for session`() {
        val json = """{"rtt":12}"""
        cache.isDuplicate(sessionId, json)
        cache.evict(sessionId)
        assertFalse(cache.isDuplicate(sessionId, json))
    }

    @Test
    fun `LRU eviction does not throw when maxEntries exceeded`() {
        val small = DeduplicationCache(maxEntries = 3)
        repeat(10) { small.isDuplicate(UUID.randomUUID(), "{}") }
        // Should not throw; size is bounded
        assertDoesNotThrow { small.isDuplicate(UUID.randomUUID(), "{}") }
    }
}
