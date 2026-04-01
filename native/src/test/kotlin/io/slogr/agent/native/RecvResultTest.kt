package io.slogr.agent.native

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class RecvResultTest {

    @Test
    fun `TIMEOUT sentinel has bytesRead=-1 and isTimeout=true`() {
        val r = RecvResult.TIMEOUT
        assertEquals(-1, r.bytesRead)
        assertNull(r.srcIp)
        assertEquals(0, r.srcPort)
        assertEquals(0, r.ttl)
        assertEquals(0, r.tos)
        assertTrue(r.isTimeout)
    }

    @Test
    fun `successful result has isTimeout=false`() {
        val r = RecvResult(
            bytesRead = 42,
            srcIp = InetAddress.getByName("10.0.0.1"),
            srcPort = 1234,
            ttl = 64,
            tos = 0
        )
        assertFalse(r.isTimeout)
        assertEquals(42, r.bytesRead)
        assertEquals("10.0.0.1", r.srcIp?.hostAddress)
        assertEquals(1234, r.srcPort)
        assertEquals(64, r.ttl)
    }

    @Test
    fun `data class equality works`() {
        val a = RecvResult.TIMEOUT
        val b = RecvResult(bytesRead = -1, srcIp = null, srcPort = 0, ttl = 0, tos = 0)
        assertEquals(a, b)
    }
}
