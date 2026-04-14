package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.testing.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.slogr.agent.contracts.*
import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.engine.traceroute.TracerouteOrchestrator
import io.slogr.agent.platform.config.AgentConfig
import io.slogr.agent.platform.otlp.OtlpExporter
import io.slogr.agent.platform.scheduler.ScheduleStore
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.util.UUID

@Timeout(30)
class CheckCommandTest {

    private val loopback = InetAddress.getLoopbackAddress()

    // ── TWAMP succeeds — no fallback ──────────────────────────────────────────

    @Test
    fun `TWAMP succeeds - output contains TWAMP`() {
        val ctx = makeCtx(twampBundle = makeGreenBundle())
        val result = SlogrCli(ctx).test("check 127.0.0.1")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("TWAMP"), result.output)
    }

    @Test
    fun `TWAMP succeeds with json format - output is valid JSON`() {
        val ctx = makeCtx(twampBundle = makeGreenBundle())
        val result = SlogrCli(ctx).test("check 127.0.0.1 --format json")
        assertEquals(0, result.statusCode, result.output)
        // Valid JSON starts with {
        val trimmed = result.output.trim()
        assertTrue(trimmed.startsWith("{"), trimmed)
        assertTrue(trimmed.contains("\"twamp\""), trimmed)
        assertTrue(trimmed.contains("\"measurement_method\""), trimmed)
    }

    // ── AGENT-004: Ground-truth RTT in check output ─────────────────────────

    @Test
    fun `TWAMP JSON output contains ground-truth RTT fields`() {
        val ctx = makeCtx(twampBundle = makeGreenBundleWithRtt())
        val result = SlogrCli(ctx).test("check 127.0.0.1 --format json")
        assertEquals(0, result.statusCode, result.output)
        val trimmed = result.output.trim()
        assertTrue(trimmed.contains("\"rtt_min_ms\""), "missing rtt_min_ms: $trimmed")
        assertTrue(trimmed.contains("\"rtt_avg_ms\""), "missing rtt_avg_ms: $trimmed")
        assertTrue(trimmed.contains("\"rtt_max_ms\""), "missing rtt_max_ms: $trimmed")
    }

    @Test
    fun `TWAMP JSON ground-truth RTT values are non-zero for reachable target`() {
        val ctx = makeCtx(twampBundle = makeGreenBundleWithRtt())
        val result = SlogrCli(ctx).test("check 127.0.0.1 --format json")
        assertEquals(0, result.statusCode, result.output)
        val trimmed = result.output.trim()
        // Verify the values are the ones we set (22.1, 25.4, 28.7), not 0.0
        assertTrue(trimmed.contains("22.1"), "rtt_min_ms should be 22.1: $trimmed")
        assertTrue(trimmed.contains("25.4"), "rtt_avg_ms should be 25.4: $trimmed")
        assertTrue(trimmed.contains("28.7"), "rtt_max_ms should be 28.7: $trimmed")
    }

    // ── TWAMP refused — fallback triggers ────────────────────────────────────

    @Test
    fun `TWAMP returns 0 packets - fallback probe runs`() {
        val ctx = makeCtx(
            twampBundle = makeZeroPacketBundle(),  // no responder
            pingStats = PingStats(received = 5, avgRttMs = 15f),
            tcpResult = TcpConnectProbe.TcpConnectResult(connectMs = 12f, port = 443, skipped = false)
        )
        val result = SlogrCli(ctx).test("check 127.0.0.1")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("no TWAMP responder"), result.output)
        assertTrue(result.output.contains("PING"), result.output)
    }

    @Test
    fun `TWAMP exception causes fallback`() {
        val ctx = makeCtx(
            twampException = RuntimeException("connection refused"),
            pingStats = PingStats(received = 3, avgRttMs = 20f),
            tcpResult = TcpConnectProbe.TcpConnectResult(connectMs = 18f, port = 80, skipped = false)
        )
        val result = SlogrCli(ctx).test("check 127.0.0.1")
        assertEquals(0, result.statusCode, result.output)
        assertTrue(result.output.contains("no TWAMP responder"), result.output)
    }

    // ── All probes fail — exit code 3 ─────────────────────────────────────────

    @Test
    fun `all probes fail - exits with code 3`() {
        val ctx = makeCtx(
            twampBundle = makeZeroPacketBundle(),
            pingStats   = PingStats(received = 0, avgRttMs = null),
            tcpResult   = TcpConnectProbe.TcpConnectResult.SKIPPED
        )
        val result = SlogrCli(ctx).test("check 127.0.0.1")
        assertEquals(3, result.statusCode, result.output)
    }

    // ── Unresolvable target — exit code 3 ────────────────────────────────────

    @Test
    fun `unresolvable target exits with code 3`() {
        val ctx = makeCtx()
        val result = SlogrCli(ctx).test("check this.host.does.not.exist.invalid")
        assertEquals(3, result.statusCode, result.output)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class PingStats(
        val received: Int,
        val avgRttMs: Float?,
        val sent: Int = 5,
        val lossPct: Float = if (received == 0) 100f else 0f
    )

    private fun makeCtx(
        twampBundle: MeasurementBundle? = null,
        twampException: Exception? = null,
        pingStats: PingStats = PingStats(received = 5, avgRttMs = 15f),
        tcpResult: TcpConnectProbe.TcpConnectResult = TcpConnectProbe.TcpConnectResult(
            connectMs = 10f, port = 443, skipped = false)
    ): CliContext {
        val engine = mockk<MeasurementEngine>()
        when {
            twampException != null ->
                coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } throws twampException
            twampBundle != null ->
                coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } returns twampBundle
            else ->
                coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } returns makeGreenBundle()
        }

        val ping = mockk<IcmpPingProbe>()
        coEvery { ping.ping(any(), any(), any()) } returns IcmpPingProbe.PingStats(
            resolvedIp = loopback.hostAddress,
            sent = pingStats.sent, received = pingStats.received,
            minRttMs = pingStats.avgRttMs?.let { it - 2f },
            avgRttMs = pingStats.avgRttMs,
            maxRttMs = pingStats.avgRttMs?.let { it + 2f },
            lossPct = pingStats.lossPct
        )

        val tcp = mockk<TcpConnectProbe>()
        coEvery { tcp.probe(any(), any(), any()) } returns tcpResult

        val traceroute = mockk<TracerouteOrchestrator>()
        coEvery { traceroute.run(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            TracerouteResult(
                sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
                direction = Direction.UPLINK, capturedAt = Clock.System.now(), hops = emptyList()
            )

        val creds = mockk<CredentialStore>(relaxed = true)
        every { creds.isConnected() } returns false
        every { creds.load() } returns null

        return CliContext(
            config               = AgentConfig(),
            engine               = engine,
            credentialStore      = creds,
            scheduleStore        = mockk(relaxed = true),
            otlpExporter         = mockk(relaxed = true),
            icmpPingProbe        = ping,
            tcpConnectProbe      = tcp,
            tracerouteOrchestrator = traceroute
        )
    }

    private fun makeGreenBundleWithRtt(): MeasurementBundle {
        val profile = internetProfile()
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
            windowTs = Clock.System.now(), profile = profile,
            rttMinMs = 22.1f, rttAvgMs = 25.4f, rttMaxMs = 28.7f,
            fwdMinRttMs = 10f, fwdAvgRttMs = 12f, fwdMaxRttMs = 15f,
            fwdJitterMs = 2f, fwdLossPct = 0f,
            revMinRttMs = 11f, revAvgRttMs = 13f, revMaxRttMs = 14f, revJitterMs = 1f,
            packetsSent = 10, packetsRecv = 10
        )
        return MeasurementBundle(twamp = result, grade = SlaGrade.GREEN)
    }

    private fun makeGreenBundle(): MeasurementBundle {
        val profile = internetProfile()
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
            windowTs = Clock.System.now(), profile = profile,
            fwdMinRttMs = 5f, fwdAvgRttMs = 12f, fwdMaxRttMs = 20f,
            fwdJitterMs = 2f, fwdLossPct = 0f, packetsSent = 10, packetsRecv = 10
        )
        return MeasurementBundle(twamp = result, grade = SlaGrade.GREEN)
    }

    private fun makeZeroPacketBundle(): MeasurementBundle {
        val profile = internetProfile()
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
            windowTs = Clock.System.now(), profile = profile,
            fwdMinRttMs = 0f, fwdAvgRttMs = 0f, fwdMaxRttMs = 0f,
            fwdJitterMs = 0f, fwdLossPct = 100f, packetsSent = 0, packetsRecv = 0
        )
        return MeasurementBundle(twamp = result, grade = SlaGrade.RED)
    }

    private fun internetProfile() = SlaProfile(
        name = "internet", nPackets = 10, intervalMs = 100L, waitTimeMs = 500L,
        dscp = 0, packetSize = 64, timingMode = TimingMode.FIXED,
        rttGreenMs = 50f, rttRedMs = 150f, jitterGreenMs = 10f, jitterRedMs = 30f,
        lossGreenPct = 1f, lossRedPct = 5f
    )
}
