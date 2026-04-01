package io.slogr.agent.native

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProbeResultTest {

    @Test
    fun `TIMEOUT sentinel has null fields and isTimeout=true`() {
        val r = ProbeResult.TIMEOUT
        assertNull(r.hopIp)
        assertNull(r.rttMs)
        assertFalse(r.reached)
        assertNull(r.icmpType)
        assertNull(r.icmpCode)
        assertTrue(r.isTimeout)
    }

    @Test
    fun `TTL expired result has reached=false and isTimeout=false`() {
        val r = ProbeResult(
            hopIp = "10.0.0.1",
            rttMs = 14.5f,
            reached = false,
            icmpType = 11,
            icmpCode = 0
        )
        assertFalse(r.isTimeout)
        assertFalse(r.reached)
        assertEquals("10.0.0.1", r.hopIp)
        assertEquals(14.5f, r.rttMs)
        assertEquals(11, r.icmpType)
        assertEquals(0, r.icmpCode)
    }

    @Test
    fun `destination reached result has reached=true`() {
        val r = ProbeResult(
            hopIp = "8.8.8.8",
            rttMs = 20.0f,
            reached = true,
            icmpType = 0,
            icmpCode = 0
        )
        assertTrue(r.reached)
        assertFalse(r.isTimeout)
    }

    @Test
    fun `data class equality works`() {
        val a = ProbeResult.TIMEOUT
        val b = ProbeResult(hopIp = null, rttMs = null, reached = false, icmpType = null, icmpCode = null)
        assertEquals(a, b)
    }
}
