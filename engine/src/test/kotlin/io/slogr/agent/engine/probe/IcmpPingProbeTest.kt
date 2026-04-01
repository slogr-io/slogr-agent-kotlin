package io.slogr.agent.engine.probe

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.slogr.agent.native.NativeProbeAdapter
import io.slogr.agent.native.ProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

class IcmpPingProbeTest {

    private val target = InetAddress.getLoopbackAddress()

    // ── All probes respond ─────────────────────────────────────────────────────

    @Test
    fun `all probes respond - stats computed correctly`() = runBlocking {
        val adapter = mockk<NativeProbeAdapter>()
        var call = 0
        every { adapter.icmpProbe(any(), eq(64), any()) } answers {
            call++
            ProbeResult("127.0.0.1", call * 10f, reached = true, icmpType = 0, icmpCode = 0)
        }

        val stats = IcmpPingProbe(adapter).ping(target, count = 3)

        assertEquals(3, stats.sent)
        assertEquals(3, stats.received)
        assertEquals(0f, stats.lossPct)
        assertEquals(10f, stats.minRttMs)
        assertEquals(20f, stats.avgRttMs)
        assertEquals(30f, stats.maxRttMs)
        assertEquals("127.0.0.1", stats.resolvedIp)
    }

    // ── All probes timeout ─────────────────────────────────────────────────────

    @Test
    fun `all probes timeout - 100 percent loss and null RTT stats`() = runBlocking {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.icmpProbe(any(), eq(64), any()) } returns ProbeResult.TIMEOUT

        val stats = IcmpPingProbe(adapter).ping(target, count = 5)

        assertEquals(5, stats.sent)
        assertEquals(0, stats.received)
        assertEquals(100f, stats.lossPct)
        assertNull(stats.minRttMs)
        assertNull(stats.avgRttMs)
        assertNull(stats.maxRttMs)
    }

    // ── Partial loss ───────────────────────────────────────────────────────────

    @Test
    fun `2 of 4 probes timeout - 50 percent loss`() = runBlocking {
        val adapter = mockk<NativeProbeAdapter>()
        var call = 0
        every { adapter.icmpProbe(any(), eq(64), any()) } answers {
            call++
            if (call % 2 == 0) ProbeResult.TIMEOUT
            else ProbeResult("1.2.3.4", 15f, reached = true, icmpType = 0, icmpCode = 0)
        }

        val stats = IcmpPingProbe(adapter).ping(target, count = 4)

        assertEquals(4, stats.sent)
        assertEquals(2, stats.received)
        assertEquals(50f, stats.lossPct)
    }

    // ── Correct TTL=64 sent ────────────────────────────────────────────────────

    @Test
    fun `probe uses TTL 64 not a traceroute TTL`() = runBlocking {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT

        IcmpPingProbe(adapter).ping(target, count = 1)

        verify(exactly = 1) { adapter.icmpProbe(any(), eq(64), any()) }
    }
}
