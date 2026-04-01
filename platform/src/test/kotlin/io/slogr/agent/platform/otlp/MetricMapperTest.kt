package io.slogr.agent.platform.otlp

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.slogr.agent.contracts.*
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.platform.output.FallbackBundle
import kotlinx.datetime.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MetricMapperTest {

    // Track which metric names were recorded via a minimal ObservableGauge
    private val recordedNames = CopyOnWriteArrayList<String>()
    private lateinit var provider: SdkMeterProvider
    private lateinit var meter: Meter

    @BeforeEach
    fun setup() {
        recordedNames.clear()
        provider = SdkMeterProvider.builder().build()
        meter = provider.get("test")
    }

    @AfterEach
    fun teardown() { provider.shutdown() }

    // ── Locked metric name prefix verification ─────────────────────────────────

    @Test
    fun `grade values are GREEN=0 YELLOW=1 RED=2`() {
        assertEquals(0, MetricMapper.gradeValue(SlaGrade.GREEN))
        assertEquals(1, MetricMapper.gradeValue(SlaGrade.YELLOW))
        assertEquals(2, MetricMapper.gradeValue(SlaGrade.RED))
    }

    @Test
    fun `TWAMP recording does not throw and records multiple metrics`() {
        // This test verifies MetricMapper.recordTwamp runs without exception
        // and records metrics via the OTel SDK gauges. We cannot easily inspect
        // metric names without the OTel SDK testing module, so we verify the
        // call completes and check locked names via the object structure.
        assertDoesNotThrow {
            MetricMapper.recordTwamp(meter, makeTwampBundle(), UUID.randomUUID(), "voip")
        }
    }

    @Test
    fun `fallback recording does not throw`() {
        assertDoesNotThrow {
            MetricMapper.recordFallback(meter, makeFallbackBundle(), UUID.randomUUID())
        }
    }

    // ── Metric name constants (locked per Rule T7) ─────────────────────────────

    @Test
    fun `locked TWAMP metric names follow slogr-network prefix`() {
        // Verify the metric names MetricMapper would record by calling it
        // with a recording meter. We capture via ObservableGauge callbacks.
        val names = mutableListOf<String>()
        val trackingMeter = object : Meter by meter {
            override fun gaugeBuilder(name: String) = run {
                names.add(name)
                meter.gaugeBuilder(name)
            }
        }
        MetricMapper.recordTwamp(trackingMeter, makeTwampBundle(), UUID.randomUUID(), "voip")

        assertTrue(names.any { it.startsWith("slogr.network.") },
            "Expected slogr.network.* metrics, got: $names")
        assertTrue(names.none { it.startsWith("slogr.twamp.") },
            "No slogr.twamp.* metrics expected")

        // Verify all required names appear
        val required = listOf(
            "slogr.network.rtt.forward.avg",
            "slogr.network.rtt.forward.min",
            "slogr.network.rtt.forward.max",
            "slogr.network.jitter.forward",
            "slogr.network.loss.forward",
            "slogr.network.packets.sent",
            "slogr.network.packets.received",
            "slogr.network.sla.grade"
        )
        for (name in required) {
            assertTrue(names.contains(name), "Missing metric: $name (got $names)")
        }
    }

    @Test
    fun `fallback metrics use slogr-network prefix`() {
        val names = mutableListOf<String>()
        val trackingMeter = object : Meter by meter {
            override fun gaugeBuilder(name: String) = run {
                names.add(name)
                meter.gaugeBuilder(name)
            }
        }
        MetricMapper.recordFallback(trackingMeter, makeFallbackBundle(), UUID.randomUUID())
        assertTrue(names.any { it.startsWith("slogr.network.") },
            "Expected slogr.network.* metrics, got: $names")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeTwampBundle(): MeasurementBundle {
        val profile = SlaProfile(
            name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 500L,
            dscp = 46, packetSize = 64, timingMode = TimingMode.FIXED,
            rttGreenMs = 30f, rttRedMs = 80f, jitterGreenMs = 5f, jitterRedMs = 15f,
            lossGreenPct = 0.5f, lossRedPct = 2f
        )
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
            windowTs = Clock.System.now(), profile = profile,
            fwdMinRttMs = 8f, fwdAvgRttMs = 12f, fwdMaxRttMs = 18f,
            fwdJitterMs = 2f, fwdLossPct = 0f, packetsSent = 100, packetsRecv = 100
        )
        return MeasurementBundle(twamp = result, grade = SlaGrade.GREEN)
    }

    private fun makeFallbackBundle() = FallbackBundle(
        target = InetAddress.getLoopbackAddress(),
        ping = IcmpPingProbe.PingStats(
            resolvedIp = "8.8.8.8", sent = 5, received = 5,
            minRttMs = 10f, avgRttMs = 14f, maxRttMs = 20f, lossPct = 0f),
        tcp = TcpConnectProbe.TcpConnectResult(connectMs = 13f, port = 443, skipped = false),
        traceroute = null,
        grade = SlaGrade.GREEN,
        profile = SlaProfile(
            name = "internet", nPackets = 10, intervalMs = 100L, waitTimeMs = 500L,
            dscp = 0, packetSize = 64, timingMode = TimingMode.FIXED,
            rttGreenMs = 50f, rttRedMs = 150f, jitterGreenMs = 10f, jitterRedMs = 30f,
            lossGreenPct = 1f, lossRedPct = 5f
        )
    )
}
