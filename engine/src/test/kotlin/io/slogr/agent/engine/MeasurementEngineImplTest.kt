package io.slogr.agent.engine

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.TwampAuthMode
import io.slogr.agent.contracts.interfaces.AsnResolver
import io.slogr.agent.native.JavaUdpTransport
import io.slogr.agent.native.NativeProbeAdapter
import io.slogr.agent.native.ProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.util.UUID

/**
 * Tests for [MeasurementEngineImpl].
 *
 * TWAMP runs in-JVM using [JavaUdpTransport] (no JNI needed).
 * Traceroute uses a mock [NativeProbeAdapter] so probes don't hit the network.
 */
@Timeout(30)
class MeasurementEngineImplTest {

    private val loopback = InetAddress.getLoopbackAddress()

    // Small 5-packet profile so tests complete quickly
    private val quickProfile = SlaProfile(
        name           = "quick-test",
        nPackets       = 5,
        intervalMs     = 10L,
        waitTimeMs     = 500L,
        dscp           = 0,
        packetSize     = 64,
        timingMode     = TimingMode.FIXED,
        rttGreenMs     = 100f,
        rttRedMs       = 300f,
        jitterGreenMs  = 20f,
        jitterRedMs    = 50f,
        lossGreenPct   = 1f,
        lossRedPct     = 5f
    )

    private var engine: MeasurementEngineImpl? = null

    @AfterEach
    fun shutdown() {
        engine?.shutdown()
        engine = null
    }

    // ── TWAMP-only: twamp() ───────────────────────────────────────────────────

    @Test
    fun `twamp-only returns MeasurementResult with packetsRecv matching nPackets`() = runBlocking {
        engine = MeasurementEngineImpl(
            adapter              = JavaUdpTransport(),
            agentId              = UUID.randomUUID(),
            localIp              = loopback,
            reflectorListenPort  = 0
        )
        val result = engine!!.twamp(loopback, engine!!.reflectorActualPort, quickProfile)
        assertEquals(5, result.packetsSent)
        assertEquals(5, result.packetsRecv)
        assertEquals(0f, result.fwdLossPct)
    }

    // ── measure() with traceroute disabled ────────────────────────────────────

    @Test
    fun `measure with traceroute=false returns bundle without traceroute`() = runBlocking {
        engine = MeasurementEngineImpl(
            adapter             = JavaUdpTransport(),
            agentId             = UUID.randomUUID(),
            localIp             = loopback,
            reflectorListenPort = 0
        )
        val bundle = engine!!.measure(loopback, engine!!.reflectorActualPort, quickProfile, traceroute = false)
        assertNotNull(bundle.twamp)
        assertNull(bundle.traceroute)
        assertNull(bundle.pathChange)
        assertNotNull(bundle.grade)
    }

    // ── measure() with traceroute enabled (mock probes) ───────────────────────

    @Test
    fun `measure with traceroute=true returns bundle with traceroute`() = runBlocking {
        val mockAdapter = buildMockAdapterWithTraceroute()
        engine = MeasurementEngineImpl(
            adapter              = mockAdapter,
            asnResolver          = mockk<AsnResolver> { coEvery { resolve(any()) } returns null },
            agentId              = UUID.randomUUID(),
            localIp              = loopback,
            reflectorListenPort  = 0
        )
        val bundle = engine!!.measure(loopback, engine!!.reflectorActualPort, quickProfile, traceroute = true)
        assertNotNull(bundle.twamp)
        assertNotNull(bundle.traceroute)
        assertTrue(bundle.traceroute!!.hops.isNotEmpty())
    }

    // ── SLA evaluation wired through measure() ───────────────────────────────

    @Test
    fun `grade is GREEN for loopback result with all-green metrics`() = runBlocking {
        engine = MeasurementEngineImpl(
            adapter             = JavaUdpTransport(),
            agentId             = UUID.randomUUID(),
            localIp             = loopback,
            reflectorListenPort = 0
        )
        val bundle = engine!!.measure(loopback, engine!!.reflectorActualPort, quickProfile, traceroute = false)
        // Loopback RTT/jitter should be well under 100ms / 20ms thresholds
        assertEquals(SlaGrade.GREEN, bundle.grade)
    }

    // ── traceroute() standalone ───────────────────────────────────────────────

    @Test
    fun `standalone traceroute returns TracerouteResult`() = runBlocking {
        val mockAdapter = buildMockAdapterWithTraceroute()
        engine = MeasurementEngineImpl(
            adapter             = mockAdapter,
            asnResolver         = mockk { coEvery { resolve(any()) } returns null },
            agentId             = UUID.randomUUID(),
            localIp             = loopback,
            reflectorListenPort = 0
        )
        val tr = engine!!.traceroute(loopback, maxHops = 5, probesPerHop = 1, mode = TracerouteMode.ICMP)
        assertTrue(tr.hops.isNotEmpty())
        assertEquals(Direction.UPLINK, tr.direction)
    }

    // ── First-run traceroute: no path change event ────────────────────────────

    @Test
    fun `first measure with traceroute has no path change event`() = runBlocking {
        val mockAdapter = buildMockAdapterWithTraceroute()
        engine = MeasurementEngineImpl(
            adapter             = mockAdapter,
            asnResolver         = mockk { coEvery { resolve(any()) } returns null },
            agentId             = UUID.randomUUID(),
            localIp             = loopback,
            reflectorListenPort = 0
        )
        val bundle = engine!!.measure(loopback, engine!!.reflectorActualPort, quickProfile, traceroute = true)
        assertNull(bundle.pathChange)
        assertFalse(bundle.traceroute!!.isHeartbeat)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a mock adapter that:
     * - delegates TWAMP UDP calls to a real [JavaUdpTransport], and
     * - returns synthetic ICMP probe results for traceroute.
     */
    private fun buildMockAdapterWithTraceroute(): NativeProbeAdapter {
        val udpTransport = JavaUdpTransport()
        return mockk<NativeProbeAdapter> {
            // Delegate all socket/UDP calls to the real JavaUdpTransport
            every { createSocket(any(), any()) }          answers { udpTransport.createSocket(firstArg(), secondArg()) }
            every { closeSocket(any()) }                  answers { udpTransport.closeSocket(firstArg()) }
            every { getLocalPort(any()) }                 answers { udpTransport.getLocalPort(firstArg()) }
            every { setTtlAndCapture(any(), any(), any()) } answers { udpTransport.setTtlAndCapture(firstArg(), secondArg(), thirdArg()) }
            every { setTos(any(), any(), any()) }         answers { udpTransport.setTos(firstArg(), secondArg(), thirdArg()) }
            every { setTimeout(any(), any()) }            answers { udpTransport.setTimeout(firstArg(), secondArg()) }
            every { connectSocket(any(), any(), any()) }  answers { udpTransport.connectSocket(firstArg(), secondArg(), thirdArg()) }
            every { sendPacket(any(), any(), any(), any()) } answers { udpTransport.sendPacket(firstArg(), secondArg(), thirdArg(), arg(3)) }
            every { recvPacket(any(), any()) }            answers { udpTransport.recvPacket(firstArg(), secondArg()) }

            // Synthetic 2-hop ICMP traceroute: hop 1 → intermediate, hop 2 → destination reached
            every { icmpProbe(any(), eq(1), any()) } returns ProbeResult("10.0.0.1", 2f, false, 11, 0)
            every { icmpProbe(any(), eq(2), any()) } returns ProbeResult("127.0.0.1", 5f, true, 0, 0)
            every { icmpProbe(any(), any(), any()) } returns ProbeResult.TIMEOUT
            every { udpProbe(any(), any(), any(), any()) } returns ProbeResult.TIMEOUT
            every { tcpProbe(any(), any(), any(), any()) } returns ProbeResult.TIMEOUT
        }
    }
}
