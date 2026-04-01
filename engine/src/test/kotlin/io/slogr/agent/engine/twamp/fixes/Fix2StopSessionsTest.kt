package io.slogr.agent.engine.twamp.fixes

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.protocol.StopSessions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * FIX-2: StopSessions.sessionCount is informational — receiver MUST accept any value.
 *
 * The Java reference checked sessionCount against the active session count and
 * closed the connection early if they differed. RFC 5357 §3.8 is explicit:
 *   "The Number of Sessions field is informational; the receiver MUST accept
 *    the Stop-Sessions command regardless of this value."
 */
class Fix2StopSessionsTest {

    private val unauthMode = TwampMode().apply { mode = TwampMode.UNAUTHENTICATED }

    private fun roundTrip(sessionCount: Int): StopSessions {
        val stop = StopSessions().apply {
            accept = 0
            this.sessionCount = sessionCount
        }
        val buf = ByteBuffer.allocate(StopSessions.SIZE)
        stop.writeTo(buf, unauthMode)
        buf.flip()
        // Skip the command byte (already consumed by the controller before readFrom)
        buf.get()
        return StopSessions.readFrom(buf, unauthMode)
    }

    @Test fun `StopSessions with sessionCount 0 is accepted`() {
        val msg = roundTrip(0)
        assertEquals(0, msg.sessionCount)
        assertEquals(0, msg.accept)
    }

    @Test fun `StopSessions with sessionCount 1 is accepted`() {
        val msg = roundTrip(1)
        assertEquals(1, msg.sessionCount)
    }

    @Test fun `StopSessions with sessionCount that mismatches active sessions is accepted`() {
        // Simulates receiving sessionCount=5 when only 1 session is active.
        // FIX-2: the field is read but never validated against anything.
        val msg = roundTrip(5)
        assertEquals(5, msg.sessionCount)
    }

    @Test fun `StopSessions with very large sessionCount is accepted`() {
        val msg = roundTrip(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, msg.sessionCount)
    }

    @Test fun `StopSessions SIZE is 32 bytes as per RFC 5357`() {
        assertEquals(32, StopSessions.SIZE)
    }
}
