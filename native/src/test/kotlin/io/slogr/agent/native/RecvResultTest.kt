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

    // ── R2-TS-01: TimestampSource enum ────────────────────────────────────

    @Test
    fun `R2-TS-01 TimestampSource has KERNEL and USERSPACE values`() {
        assertEquals(2, TimestampSource.entries.size)
        assertNotNull(TimestampSource.valueOf("KERNEL"))
        assertNotNull(TimestampSource.valueOf("USERSPACE"))
    }

    // ── R2-TS-02: USERSPACE default and KERNEL when timestamp present ─────

    @Test
    fun `R2-TS-02 default timestampSource is USERSPACE and zero NTP`() {
        val r = RecvResult(bytesRead = 14, srcIp = null, srcPort = 862, ttl = 64, tos = 0)
        assertEquals(TimestampSource.USERSPACE, r.timestampSource)
        assertEquals(0L, r.kernelTimestampNtp)
    }

    @Test
    fun `R2-TS-02 non-zero kernelTimestampNtp with KERNEL source is preserved`() {
        val ntpTs = 0x00000001_80000000L   // arbitrary NTP value (fits in signed Long)
        val r = RecvResult(
            bytesRead          = 14,
            srcIp              = null,
            srcPort            = 862,
            ttl                = 64,
            tos                = 0,
            kernelTimestampNtp = ntpTs,
            timestampSource    = TimestampSource.KERNEL
        )
        assertEquals(TimestampSource.KERNEL, r.timestampSource)
        assertEquals(ntpTs, r.kernelTimestampNtp)
    }
}
