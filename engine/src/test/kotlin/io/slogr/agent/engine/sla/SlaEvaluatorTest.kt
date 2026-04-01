package io.slogr.agent.engine.sla

import io.slogr.agent.contracts.SlaGrade.GREEN
import io.slogr.agent.contracts.SlaGrade.RED
import io.slogr.agent.contracts.SlaGrade.YELLOW
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.contracts.MeasurementResult
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class SlaEvaluatorTest {

    private val profile = SlaProfile(
        name             = "test",
        nPackets         = 10,
        intervalMs       = 50,
        waitTimeMs       = 2000,
        dscp             = 0,
        packetSize       = 200,
        timingMode       = TimingMode.FIXED,
        rttGreenMs       = 100f,
        rttRedMs         = 200f,
        jitterGreenMs    = 20f,
        jitterRedMs      = 50f,
        lossGreenPct     = 1f,
        lossRedPct       = 5f
    )

    // ── GREEN ─────────────────────────────────────────────────────────────────

    @Test
    fun `all metrics under green thresholds returns GREEN`() {
        assertEquals(GREEN, SlaEvaluator.evaluate(result(rtt = 50f, jitter = 10f, loss = 0f), profile))
    }

    @Test
    fun `metrics exactly at green threshold returns GREEN`() {
        assertEquals(GREEN, SlaEvaluator.evaluate(result(rtt = 100f, jitter = 20f, loss = 1f), profile))
    }

    // ── YELLOW ────────────────────────────────────────────────────────────────

    @Test
    fun `RTT between green and red returns YELLOW`() {
        assertEquals(YELLOW, SlaEvaluator.evaluate(result(rtt = 150f, jitter = 10f, loss = 0f), profile))
    }

    @Test
    fun `jitter between green and red returns YELLOW`() {
        assertEquals(YELLOW, SlaEvaluator.evaluate(result(rtt = 50f, jitter = 35f, loss = 0f), profile))
    }

    @Test
    fun `loss between green and red returns YELLOW`() {
        assertEquals(YELLOW, SlaEvaluator.evaluate(result(rtt = 50f, jitter = 10f, loss = 3f), profile))
    }

    // ── RED ───────────────────────────────────────────────────────────────────

    @Test
    fun `RTT above red threshold returns RED`() {
        assertEquals(RED, SlaEvaluator.evaluate(result(rtt = 250f, jitter = 10f, loss = 0f), profile))
    }

    @Test
    fun `jitter above red threshold returns RED`() {
        assertEquals(RED, SlaEvaluator.evaluate(result(rtt = 50f, jitter = 60f, loss = 0f), profile))
    }

    @Test
    fun `loss above red threshold returns RED`() {
        assertEquals(RED, SlaEvaluator.evaluate(result(rtt = 50f, jitter = 10f, loss = 6f), profile))
    }

    @Test
    fun `RED takes priority over YELLOW when both exceeded`() {
        // RTT is YELLOW range, loss is RED — result should be RED
        assertEquals(RED, SlaEvaluator.evaluate(result(rtt = 150f, jitter = 10f, loss = 10f), profile))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun result(rtt: Float, jitter: Float, loss: Float): MeasurementResult =
        MeasurementResult(
            sessionId     = UUID.randomUUID(),
            pathId        = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(),
            destAgentId   = UUID.randomUUID(),
            srcCloud      = "aws",
            srcRegion     = "us-east-1",
            dstCloud      = "aws",
            dstRegion     = "us-west-2",
            windowTs      = Clock.System.now(),
            profile       = profile,
            fwdMinRttMs   = rtt * 0.8f,
            fwdAvgRttMs   = rtt,
            fwdMaxRttMs   = rtt * 1.2f,
            fwdJitterMs   = jitter,
            fwdLossPct    = loss,
            packetsSent   = 10,
            packetsRecv   = 10
        )
}
