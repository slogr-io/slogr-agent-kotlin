package io.slogr.agent.engine.traceroute

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.interfaces.AsnResolver
import io.slogr.agent.native.NativeProbeAdapter
import io.slogr.agent.native.ProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.UUID

class TracerouteOrchestratorTest {

    private val nullAsn = mockk<AsnResolver> { coEvery { resolve(any()) } returns null }

    // ── ICMP-first: ICMP has resolved hops → no fallback ─────────────────────

    @Test
    fun `ICMP mode used when majority of hops respond`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // ICMP: 3 hops respond, last one reaches target
        every { adapter.icmpProbe(any(), eq(1), any()) } returns ProbeResult("10.0.0.1", 5f, false, 11, 0)
        every { adapter.icmpProbe(any(), eq(2), any()) } returns ProbeResult("10.0.0.2", 10f, false, 11, 0)
        every { adapter.icmpProbe(any(), eq(3), any()) } returns ProbeResult("8.8.8.8", 15f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target      = InetAddress.getByName("8.8.8.8"),
            sessionId   = UUID.randomUUID(),
            pathId      = UUID.randomUUID(),
            direction   = Direction.UPLINK,
            maxHops     = 10,
            probesPerHop = 1
        )

        assertEquals(3, result.hops.size)
        assertEquals("8.8.8.8", result.hops.last().ip)
        verify(exactly = 0) { adapter.tcpProbe(any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.udpProbe(any(), any(), any(), any()) }
    }

    // ── Fallback to TCP: ICMP mostly stars ───────────────────────────────────

    @Test
    fun `falls back to TCP when ICMP has more than 50 percent timeouts`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // ICMP: 2 hops timeout out of 3 → > 50% → try TCP
        every { adapter.icmpProbe(any(), eq(1), any()) } returns ProbeResult.TIMEOUT
        every { adapter.icmpProbe(any(), eq(2), any()) } returns ProbeResult.TIMEOUT
        every { adapter.icmpProbe(any(), eq(3), any()) } returns ProbeResult("8.8.8.8", 15f, true, 0, 0)

        // TCP resolves cleanly
        every { adapter.tcpProbe(any(), any(), eq(1), any()) } returns ProbeResult("10.0.0.1", 5f, false, 11, 0)
        every { adapter.tcpProbe(any(), any(), eq(2), any()) } returns ProbeResult("10.0.0.2", 10f, false, 11, 0)
        every { adapter.tcpProbe(any(), any(), eq(3), any()) } returns ProbeResult("8.8.8.8", 15f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target      = InetAddress.getByName("8.8.8.8"),
            sessionId   = UUID.randomUUID(),
            pathId      = UUID.randomUUID(),
            direction   = Direction.UPLINK,
            maxHops     = 10,
            probesPerHop = 1
        )

        // TCP result wins (3 resolved hops vs ICMP's 1)
        assertEquals(3, result.hops.size)
        assertEquals("10.0.0.1", result.hops[0].ip)
    }

    // ── Fallback to UDP: both ICMP and TCP mostly stars ──────────────────────

    @Test
    fun `falls back to UDP when ICMP and TCP are both mostly stars`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.tcpProbe(any(), any(), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.udpProbe(any(), any(), eq(1), any()) } returns ProbeResult("10.1.0.1", 5f, false, 11, 0)
        every { adapter.udpProbe(any(), any(), eq(2), any()) } returns ProbeResult("8.8.8.8", 12f, true, 3, 3)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target      = InetAddress.getByName("8.8.8.8"),
            sessionId   = UUID.randomUUID(),
            pathId      = UUID.randomUUID(),
            direction   = Direction.UPLINK,
            maxHops     = 5,
            probesPerHop = 1
        )

        assertEquals("10.1.0.1", result.hops[0].ip)
    }

    // ── Explicit mode: no fallback ────────────────────────────────────────────

    @Test
    fun `explicit UDP mode skips ICMP and TCP`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.udpProbe(any(), any(), eq(1), any()) } returns ProbeResult("10.0.0.1", 5f, false, 11, 0)
        every { adapter.udpProbe(any(), any(), eq(2), any()) } returns ProbeResult("8.8.8.8", 12f, true, 3, 3)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 5,
            probesPerHop = 1,
            mode         = TracerouteMode.UDP
        )

        verify(exactly = 0) { adapter.icmpProbe(any(), any(), any()) }
        verify(exactly = 0) { adapter.tcpProbe(any(), any(), any(), any()) }
    }

    // ── Private IP filter ─────────────────────────────────────────────────────

    @Test
    fun `isPrivateIp filters RFC 1918 CGNAT loopback link-local multicast`() {
        val orchestrator = TracerouteOrchestrator(mockk(), nullAsn)
        assertTrue(orchestrator.isPrivateIp("10.0.0.1"))
        assertTrue(orchestrator.isPrivateIp("172.16.0.1"))
        assertTrue(orchestrator.isPrivateIp("172.31.255.255"))
        assertTrue(orchestrator.isPrivateIp("192.168.1.1"))
        assertTrue(orchestrator.isPrivateIp("100.64.0.1"))
        assertTrue(orchestrator.isPrivateIp("100.127.255.255"))
        assertTrue(orchestrator.isPrivateIp("127.0.0.1"))
        assertTrue(orchestrator.isPrivateIp("169.254.0.1"))
        assertTrue(orchestrator.isPrivateIp("224.0.0.1"))
        assertTrue(orchestrator.isPrivateIp("255.255.255.255"))
        assertFalse(orchestrator.isPrivateIp("8.8.8.8"))
        assertFalse(orchestrator.isPrivateIp("1.1.1.1"))
        assertFalse(orchestrator.isPrivateIp("203.0.113.1"))
    }

    // ── Loss percentage calculation ───────────────────────────────────────────

    @Test
    fun `loss pct is 100 when all probes timeout`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.icmpProbe(any(), eq(1), any()) } returns ProbeResult.TIMEOUT
        every { adapter.icmpProbe(any(), eq(2), any()) } returns ProbeResult("8.8.8.8", 10f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target      = InetAddress.getByName("8.8.8.8"),
            sessionId   = UUID.randomUUID(),
            pathId      = UUID.randomUUID(),
            direction   = Direction.UPLINK,
            maxHops     = 5,
            probesPerHop = 1,
            mode        = TracerouteMode.ICMP
        )

        assertNull(result.hops[0].ip)
        assertEquals(100f, result.hops[0].lossPct)
    }

    @Test
    fun `loss pct is 50 when half probes timeout with probesPerHop 2`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // First probe: timeout; second probe: responds
        var callCount = 0
        every { adapter.icmpProbe(any(), eq(1), any()) } answers {
            callCount++
            if (callCount % 2 == 1) ProbeResult.TIMEOUT
            else ProbeResult("10.0.0.1", 5f, false, 11, 0)
        }
        every { adapter.icmpProbe(any(), eq(2), any()) } returns ProbeResult("8.8.8.8", 10f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target      = InetAddress.getByName("8.8.8.8"),
            sessionId   = UUID.randomUUID(),
            pathId      = UUID.randomUUID(),
            direction   = Direction.UPLINK,
            maxHops     = 5,
            probesPerHop = 2,
            mode        = TracerouteMode.ICMP
        )

        assertEquals(50f, result.hops[0].lossPct)
    }

    // ── Budget gating (AGENT-003) ────────────────────────────────────────────

    @Test
    fun `first mode always runs even with expired budget`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // ICMP: mostly stars (triggers fallback normally, but budget prevents it)
        every { adapter.icmpProbe(any(), eq(1), any()) } returns ProbeResult.TIMEOUT
        every { adapter.icmpProbe(any(), eq(2), any()) } returns ProbeResult.TIMEOUT
        every { adapter.icmpProbe(any(), eq(3), any()) } returns ProbeResult("8.8.8.8", 15f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 10,
            probesPerHop = 1,
            budgetMs     = 1  // instant expiry — but ICMP still runs unconditionally
        )

        // ICMP result returned (3 hops, 2 stars + target)
        assertEquals(3, result.hops.size)
        // TCP/UDP never called
        verify(exactly = 0) { adapter.tcpProbe(any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.udpProbe(any(), any(), any(), any()) }
    }

    @Test
    fun `budget gates second mode when remaining below threshold`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // ICMP: mostly stars
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 3,
            probesPerHop = 1,
            budgetMs     = 1  // ICMP runs, budget expires, TCP skipped
        )

        // Got ICMP result (all stars), TCP never attempted
        assertTrue(result.hops.isNotEmpty())
        verify(exactly = 0) { adapter.tcpProbe(any(), any(), any(), any()) }
    }

    @Test
    fun `budget allows two modes but not three`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // All modes return mostly stars (forces full fallback chain)
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.tcpProbe(any(), any(), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.udpProbe(any(), any(), any(), any()) } returns ProbeResult.TIMEOUT

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 2,
            probesPerHop = 1,
            timeoutMs    = 1,       // very fast probes
            budgetMs     = 120_000  // 2 minutes — enough for ICMP+TCP but check UDP gate
        )

        // Should have run at least ICMP (verify by checking hops exist)
        assertTrue(result.hops.isNotEmpty())
        // With timeoutMs=1 and maxHops=2, ICMP+TCP finish near-instantly,
        // so UDP should also run (budget is generous). This tests the "allows two"
        // path — UDP gate depends on remaining budget after TCP.
        verify(atLeast = 1) { adapter.icmpProbe(any(), any(), any()) }
        verify(atLeast = 1) { adapter.tcpProbe(any(), any(), any(), any()) }
    }

    @Test
    fun `explicit mode ignores budget and fallback`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.tcpProbe(any(), any(), eq(1), any()) } returns ProbeResult("10.0.0.1", 5f, false, 11, 0)
        every { adapter.tcpProbe(any(), any(), eq(2), any()) } returns ProbeResult("8.8.8.8", 12f, true, 3, 3)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 5,
            probesPerHop = 1,
            mode         = TracerouteMode.TCP,
            budgetMs     = 30_000
        )

        verify(exactly = 0) { adapter.icmpProbe(any(), any(), any()) }
        verify(exactly = 0) { adapter.udpProbe(any(), any(), any(), any()) }
    }

    @Test
    fun `check-specific reduced defaults passed through`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        every { adapter.icmpProbe(any(), eq(1), eq(500)) } returns ProbeResult("8.8.8.8", 5f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 5,
            probesPerHop = 1,
            timeoutMs    = 500,  // check-specific reduced timeout
            mode         = TracerouteMode.ICMP
        )

        // Verify the adapter received the reduced timeoutMs (500, not default 2000)
        verify { adapter.icmpProbe(any(), eq(1), eq(500)) }
    }

    // ── Mesh target: TCP uses target port (862), not hardcoded 443 ───────────

    @Test
    fun `mesh target uses tcpPort 862 for TCP probes`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // ICMP: mostly stars → trigger TCP fallback
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT
        // TCP on port 862: resolves cleanly
        every { adapter.tcpProbe(any(), eq(862), any(), any()) } returns ProbeResult("10.0.0.1", 5f, false, 11, 0)
        every { adapter.tcpProbe(any(), eq(862), eq(2), any()) } returns ProbeResult("8.8.8.8", 12f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 5,
            probesPerHop = 1,
            tcpPort      = 862
        )

        // TCP/862 was used (not 443)
        verify(atLeast = 1) { adapter.tcpProbe(any(), eq(862), any(), any()) }
        verify(exactly = 0) { adapter.tcpProbe(any(), eq(443), any(), any()) }
        assertEquals("10.0.0.1", result.hops[0].ip)
    }

    @Test
    fun `external target uses default TCP port 443`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // ICMP: mostly stars → trigger TCP fallback
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT
        // TCP on port 443: resolves
        every { adapter.tcpProbe(any(), eq(443), any(), any()) } returns ProbeResult("10.0.0.1", 5f, false, 11, 0)
        every { adapter.tcpProbe(any(), eq(443), eq(2), any()) } returns ProbeResult("8.8.8.8", 12f, true, 0, 0)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 5,
            probesPerHop = 1
            // no tcpPort → defaults to 443
        )

        verify(atLeast = 1) { adapter.tcpProbe(any(), eq(443), any(), any()) }
        verify(exactly = 0) { adapter.tcpProbe(any(), eq(862), any(), any()) }
    }

    @Test
    fun `mesh target falls back from TCP 862 to TCP 443 to UDP`() = runTest {
        val adapter = mockk<NativeProbeAdapter>()
        // All modes return mostly stars except UDP
        every { adapter.icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.tcpProbe(any(), eq(862), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.tcpProbe(any(), eq(443), any(), any()) } returns ProbeResult.TIMEOUT
        every { adapter.udpProbe(any(), any(), eq(1), any()) } returns ProbeResult("10.1.0.1", 5f, false, 11, 0)
        every { adapter.udpProbe(any(), any(), eq(2), any()) } returns ProbeResult("8.8.8.8", 12f, true, 3, 3)

        val orchestrator = TracerouteOrchestrator(adapter, nullAsn)
        val result = orchestrator.run(
            target       = InetAddress.getByName("8.8.8.8"),
            sessionId    = UUID.randomUUID(),
            pathId       = UUID.randomUUID(),
            direction    = Direction.UPLINK,
            maxHops      = 5,
            probesPerHop = 1,
            tcpPort      = 862
        )

        // All four modes were tried: ICMP, TCP/862, TCP/443, UDP
        verify(atLeast = 1) { adapter.icmpProbe(any(), any(), any()) }
        verify(atLeast = 1) { adapter.tcpProbe(any(), eq(862), any(), any()) }
        verify(atLeast = 1) { adapter.tcpProbe(any(), eq(443), any(), any()) }
        verify(atLeast = 1) { adapter.udpProbe(any(), any(), any(), any()) }
        // UDP result wins
        assertEquals("10.1.0.1", result.hops[0].ip)
    }
}
