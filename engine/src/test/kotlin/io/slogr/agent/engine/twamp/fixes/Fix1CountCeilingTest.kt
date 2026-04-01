package io.slogr.agent.engine.twamp.fixes

import io.slogr.agent.engine.twamp.TwampConstants
import io.slogr.agent.engine.twamp.protocol.ServerGreeting
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * FIX-1: No upper ceiling on PBKDF2 count in ServerGreeting.
 *
 * The Java reference capped count at 32768 and rejected ServerGreeting
 * messages from Cisco IOS devices that send count=65536.
 * RFC 5357 places no upper bound — any count >= 1024 MUST be accepted.
 */
class Fix1CountCeilingTest {

    @Test fun `TwampConstants has no MAX_COUNT field`() {
        // Verify the ceiling constant was removed entirely.
        val fields = TwampConstants::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("MAX_COUNT"), "MAX_COUNT ceiling must not exist")
        assertFalse(fields.contains("DEFAULT_MAX_COUNT"), "DEFAULT_MAX_COUNT ceiling must not exist")
    }

    @Test fun `ServerGreeting round-trips with count 65536 (Cisco IOS value)`() {
        val greeting = ServerGreeting().apply {
            modes = 1
            count = 65536
        }
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        greeting.writeTo(buf)
        buf.flip()

        val decoded = ServerGreeting.readFrom(buf)
        assertEquals(65536, decoded.count, "count=65536 must survive encode/decode")
    }

    @Test fun `ServerGreeting round-trips with count 1048576`() {
        val greeting = ServerGreeting().apply {
            modes = 1
            count = 1_048_576
        }
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        greeting.writeTo(buf)
        buf.flip()

        val decoded = ServerGreeting.readFrom(buf)
        assertEquals(1_048_576, decoded.count)
    }

    @Test fun `ServerGreeting round-trips with minimum count 1024`() {
        val greeting = ServerGreeting().apply {
            modes = 1
            count = TwampConstants.MIN_COUNT
        }
        val buf = ByteBuffer.allocate(ServerGreeting.SIZE)
        greeting.writeTo(buf)
        buf.flip()

        val decoded = ServerGreeting.readFrom(buf)
        assertEquals(TwampConstants.MIN_COUNT, decoded.count)
    }
}
