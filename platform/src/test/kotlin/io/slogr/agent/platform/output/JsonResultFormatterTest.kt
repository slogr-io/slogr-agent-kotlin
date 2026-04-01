package io.slogr.agent.platform.output

import io.slogr.agent.contracts.*
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.UUID

class JsonResultFormatterTest {

    private val formatter = JsonResultFormatter()
    private val loopback  = InetAddress.getLoopbackAddress()
    private val profile   = voipProfile()

    // ── TWAMP result ───────────────────────────────────────────────────────────

    @Test
    fun `format TWAMP result is valid JSON with measurement_method twamp`() {
        val output = formatter.format(loopback, makeTwampBundle(), "voip")
        val obj = Json.parseToJsonElement(output).jsonObject
        assertEquals("twamp", obj["measurement_method"]?.jsonPrimitive?.content)
        assertEquals("GREEN", obj["grade"]?.jsonPrimitive?.content)
        assertNotNull(obj["twamp"])
    }

    @Test
    fun `format TWAMP result twamp object contains expected fields`() {
        val output = formatter.format(loopback, makeTwampBundle(), "voip")
        val twamp = Json.parseToJsonElement(output).jsonObject["twamp"]!!.jsonObject
        assertNotNull(twamp["packets_sent"])
        assertNotNull(twamp["packets_recv"])
        assertNotNull(twamp["fwd_avg_rtt_ms"])
        assertNotNull(twamp["fwd_loss_pct"])
        assertNotNull(twamp["session_id"])
    }

    // ── Fallback result ────────────────────────────────────────────────────────

    @Test
    fun `formatFallback is valid JSON with measurement_method icmp`() {
        val output = formatter.formatFallback(makeFallbackBundle())
        val obj = Json.parseToJsonElement(output).jsonObject
        assertEquals("icmp", obj["measurement_method"]?.jsonPrimitive?.content)
        assertNotNull(obj["ping"])
    }

    @Test
    fun `formatFallback tcp section absent when skipped`() {
        val bundle = makeFallbackBundle(tcp = TcpConnectProbe.TcpConnectResult.SKIPPED)
        val obj = Json.parseToJsonElement(formatter.formatFallback(bundle)).jsonObject
        assertNull(obj["tcp"])
    }

    @Test
    fun `formatFallback tcp section present when connected`() {
        val tcp = TcpConnectProbe.TcpConnectResult(connectMs = 9.5f, port = 443, skipped = false)
        val obj = Json.parseToJsonElement(formatter.formatFallback(makeFallbackBundle(tcp = tcp))).jsonObject
        assertNotNull(obj["tcp"])
        assertEquals("443", obj["tcp"]!!.jsonObject["port"]?.jsonPrimitive?.content)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeTwampBundle(): MeasurementBundle {
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
            windowTs = Clock.System.now(), profile = profile,
            fwdMinRttMs = 8f, fwdAvgRttMs = 12f, fwdMaxRttMs = 18f,
            fwdJitterMs = 2f, fwdLossPct = 0f,
            packetsSent = 100, packetsRecv = 100
        )
        return MeasurementBundle(twamp = result, grade = SlaGrade.GREEN)
    }

    private fun makeFallbackBundle(
        tcp: TcpConnectProbe.TcpConnectResult = TcpConnectProbe.TcpConnectResult(
            connectMs = 13.2f, port = 443, skipped = false)
    ) = FallbackBundle(
        target    = loopback,
        ping      = IcmpPingProbe.PingStats(
            resolvedIp = "104.18.12.33", sent = 5, received = 5,
            minRttMs = 10f, avgRttMs = 14f, maxRttMs = 20f, lossPct = 0f),
        tcp       = tcp,
        traceroute = null,
        grade     = SlaGrade.GREEN,
        profile   = profile
    )

    private fun voipProfile() = SlaProfile(
        name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 500L,
        dscp = 46, packetSize = 64, timingMode = TimingMode.FIXED,
        rttGreenMs = 30f, rttRedMs = 80f,
        jitterGreenMs = 5f, jitterRedMs = 15f,
        lossGreenPct = 0.5f, lossRedPct = 2f
    )
}
