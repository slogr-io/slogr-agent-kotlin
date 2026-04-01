package io.slogr.agent.engine.clock

import io.slogr.agent.engine.twamp.controller.PacketRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class VirtualClockEstimatorTest {

    private fun packet(fwd: Float, rev: Float, seq: Int = 0) = PacketRecord(
        seq = seq, txNtp = 0L, rxNtp = 0L,
        fwdDelayMs = fwd, revDelayMs = rev,
        reflectorProcNs = 0L, txTtl = 64, rxTtl = 64
    )

    // ── R2-CLOCK-01: symmetric path → offset near 0 ───────────────────────

    @Test
    fun `R2-CLOCK-01 symmetric path yields offset near zero`() {
        val packets = (1..10).map { packet(fwd = 15f, rev = 15f, seq = it) }
        val offset = VirtualClockEstimator.estimate(packets)
        assertNotNull(offset)
        assertTrue(abs(offset!!) < 0.01f, "Expected ~0ms offset, got $offset")
    }

    // ── R2-CLOCK-02: 100ms clock skew → offset ≈ 100ms ───────────────────

    @Test
    fun `R2-CLOCK-02 100ms clock skew is estimated correctly`() {
        // Actual one-way delay 200ms each way, reflector clock ahead 100ms
        // fwdDelayMs = 200 + 100 = 300ms, revDelayMs = 200 - 100 = 100ms
        val packets = (1..10).map { i ->
            val jitter = i % 3 * 1f    // small variation
            packet(fwd = 300f + jitter, rev = 100f + jitter, seq = i)
        }
        val offset = VirtualClockEstimator.estimate(packets)
        assertNotNull(offset)
        assertTrue(abs(offset!! - 100f) < 5f,
            "Expected offset ≈ 100ms, got $offset")
    }

    // ── Empty input → null ────────────────────────────────────────────────

    @Test
    fun `empty packet list returns null`() {
        assertNull(VirtualClockEstimator.estimate(emptyList()))
    }

    // ── Single packet ─────────────────────────────────────────────────────

    @Test
    fun `single packet returns half the delay difference`() {
        val offset = VirtualClockEstimator.estimate(listOf(packet(fwd = 80f, rev = 20f)))
        assertNotNull(offset)
        assertEquals(30f, offset!!, 0.001f)  // (80 - 20) / 2
    }

    // ── Selects lowest-RTT samples for estimate ───────────────────────────

    @Test
    fun `high-RTT outlier packets do not skew the estimate`() {
        // 5 clean packets with RTT=30ms and offset=50ms
        val good = (1..5).map { packet(fwd = 65f, rev = 15f, seq = it) }
        // 10 noisy high-RTT packets with wildly different offset
        val noisy = (6..15).map { packet(fwd = 1000f, rev = 1f, seq = it) }

        val offset = VirtualClockEstimator.estimate(good + noisy)
        assertNotNull(offset)
        // Good packets contribute: offset = (65 - 15) / 2 = 25ms
        // But noisy packets have RTT=1001ms > good RTT=80ms, so top-5 are good
        assertTrue(abs(offset!! - 25f) < 2f,
            "Expected offset ≈ 25ms from low-RTT samples, got $offset")
    }

    // ── Median of top-K is used (not mean) ───────────────────────────────

    @Test
    fun `median removes single outlier in top-5`() {
        // Top 5 sorted by RTT (ascending): 4 with offset=10ms, 1 with offset=50ms
        val packets = listOf(
            packet(fwd = 25f, rev = 5f, seq = 1),    // rtt=30, offset=10
            packet(fwd = 26f, rev = 6f, seq = 2),    // rtt=32, offset=10
            packet(fwd = 27f, rev = 7f, seq = 3),    // rtt=34, offset=10
            packet(fwd = 75f, rev = -25f, seq = 4),  // rtt=50, offset=50  (outlier)
            packet(fwd = 28f, rev = 8f, seq = 5),    // rtt=36, offset=10
            packet(fwd = 500f, rev = 10f, seq = 6),  // rtt=510 — excluded from top-5
        )
        val offset = VirtualClockEstimator.estimate(packets)
        assertNotNull(offset)
        // Top-5 by RTT: seqs 1,2,3,5 (offset=10) and seq 4 (offset=50)
        // Sorted offsets: [10, 10, 10, 10, 50] → median = 10
        assertEquals(10f, offset!!, 0.001f)
    }
}
