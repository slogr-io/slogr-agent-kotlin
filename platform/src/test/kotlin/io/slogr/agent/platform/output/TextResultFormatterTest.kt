package io.slogr.agent.platform.output

import io.slogr.agent.contracts.*
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.platform.config.AgentState
import io.slogr.agent.platform.config.AirGapDetector
import kotlinx.datetime.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.UUID

class TextResultFormatterTest {

    @BeforeEach
    @AfterEach
    fun resetAirGap() { AirGapDetector.resetForTest() }

    private val formatter = TextResultFormatter(AgentState.CONNECTED)  // no footer by default
    private val loopback  = InetAddress.getLoopbackAddress()
    private val profile   = voipProfile()

    // ── TWAMP result ───────────────────────────────────────────────────────────

    @Test
    fun `format TWAMP result contains key fields`() {
        val bundle = makeTwampBundle()
        val output = formatter.format(loopback, bundle, "voip")
        assertTrue(output.contains("TWAMP"), output)
        assertTrue(output.contains("100"), output)         // packets sent
        assertTrue(output.contains("GREEN"), output)
        assertTrue(output.contains("voip"), output)
    }

    @Test
    fun `format TWAMP result with traceroute includes TRACE section`() {
        val tr = TracerouteResult(
            sessionId  = UUID.randomUUID(),
            pathId     = UUID.randomUUID(),
            direction  = Direction.UPLINK,
            capturedAt = Clock.System.now(),
            hops = listOf(
                TracerouteHop(ttl = 1, ip = "10.0.0.1", rttMs = 2.5f, lossPct = 0f),
                TracerouteHop(ttl = 2, ip = "8.8.8.8",  rttMs = 14f,  lossPct = 0f, asn = 15169, asnName = "Google")
            )
        )
        val bundle = makeTwampBundle(traceroute = tr)
        val output = formatter.format(loopback, bundle, "voip")
        assertTrue(output.contains("TRACE"), output)
        assertTrue(output.contains("10.0.0.1"), output)
        assertTrue(output.contains("AS15169"), output)
    }

    // ── Fallback result ────────────────────────────────────────────────────────

    @Test
    fun `formatFallback contains no TWAMP responder header`() {
        val bundle = makeFallbackBundle()
        val output = formatter.formatFallback(bundle)
        assertTrue(output.contains("no TWAMP responder"), output)
        assertTrue(output.contains("PING"), output)
    }

    @Test
    fun `formatFallback contains TCP section when tcp not skipped`() {
        val tcp = TcpConnectProbe.TcpConnectResult(connectMs = 13.2f, port = 443, skipped = false)
        val bundle = makeFallbackBundle(tcp = tcp)
        val output = formatter.formatFallback(bundle)
        assertTrue(output.contains("TCP"), output)
        assertTrue(output.contains("443"), output)
        assertTrue(output.contains("13.2"), output)
    }

    @Test
    fun `formatFallback skips TCP block when tcp skipped`() {
        val bundle = makeFallbackBundle(tcp = TcpConnectProbe.TcpConnectResult.SKIPPED)
        val output = formatter.formatFallback(bundle)
        assertTrue(output.contains("not reachable"), output)
    }

    @Test
    fun `formatFallback all probes timeout shows 100 percent loss`() {
        val ping = IcmpPingProbe.PingStats(
            resolvedIp = null, sent = 5, received = 0,
            minRttMs = null, avgRttMs = null, maxRttMs = null, lossPct = 100f
        )
        val bundle = makeFallbackBundle(ping = ping)
        val output = formatter.formatFallback(bundle)
        assertTrue(output.contains("100.0% loss"), output)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeTwampBundle(traceroute: TracerouteResult? = null): MeasurementBundle {
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
            windowTs = Clock.System.now(), profile = profile,
            fwdMinRttMs = 8f, fwdAvgRttMs = 12f, fwdMaxRttMs = 18f,
            fwdJitterMs = 2f, fwdLossPct = 0f,
            packetsSent = 100, packetsRecv = 100
        )
        return MeasurementBundle(twamp = result, traceroute = traceroute, grade = SlaGrade.GREEN)
    }

    private fun makeFallbackBundle(
        ping: IcmpPingProbe.PingStats = IcmpPingProbe.PingStats(
            resolvedIp = "104.18.12.33", sent = 5, received = 5,
            minRttMs = 12.1f, avgRttMs = 14.3f, maxRttMs = 18.7f, lossPct = 0f
        ),
        tcp: TcpConnectProbe.TcpConnectResult = TcpConnectProbe.TcpConnectResult(
            connectMs = 13.2f, port = 443, skipped = false
        )
    ) = FallbackBundle(
        target    = loopback,
        ping      = ping,
        tcp       = tcp,
        traceroute = null,
        grade     = SlaGrade.GREEN,
        profile   = profile
    )

    // ── Footer nudge (ANONYMOUS state) ───────────────────────────────────────

    @Test
    fun `ANONYMOUS formatter appends footer to TWAMP output`() {
        val anon   = TextResultFormatter(AgentState.ANONYMOUS)
        val output = anon.format(loopback, makeTwampBundle(), "voip")
        assertTrue(output.contains("slogr.io"), "Footer should contain slogr.io")
    }

    @Test
    fun `REGISTERED formatter has no footer`() {
        val reg    = TextResultFormatter(AgentState.REGISTERED)
        val output = reg.format(loopback, makeTwampBundle(), "voip")
        assertFalse(output.trimEnd().endsWith(".io\n") || output.contains("slogr.io/enterprise"),
            "REGISTERED state should not show footer")
    }

    @Test
    fun `ANONYMOUS formatter appends footer to fallback output`() {
        val anon   = TextResultFormatter(AgentState.ANONYMOUS)
        val output = anon.formatFallback(makeFallbackBundle())
        assertTrue(output.contains("slogr.io"), "Footer should contain slogr.io")
    }

    private fun voipProfile() = SlaProfile(
        name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 500L,
        dscp = 46, packetSize = 64, timingMode = TimingMode.FIXED,
        rttGreenMs = 30f, rttRedMs = 80f,
        jitterGreenMs = 5f, jitterRedMs = 15f,
        lossGreenPct = 0.5f, lossRedPct = 2f
    )
}
