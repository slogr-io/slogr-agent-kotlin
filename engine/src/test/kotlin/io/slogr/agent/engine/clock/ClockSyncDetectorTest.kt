package io.slogr.agent.engine.clock

import io.slogr.agent.contracts.ClockSyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClockSyncDetectorTest {

    private fun classify(
        fwdAvgMs: Float,
        revAvgMs: Float,
        bestOffsetMs: Float?,
        senderNtpSynced: Boolean = false,
        reflectorSynced: Boolean = false
    ) = ClockSyncDetector.classify(fwdAvgMs, revAvgMs, bestOffsetMs, senderNtpSynced, reflectorSynced)

    // ── R2-CLOCK-01: SYNCED when both NTP flags set ───────────────────────

    @Test
    fun `R2-CLOCK-01 both NTP-synced yields SYNCED`() {
        assertEquals(ClockSyncStatus.SYNCED,
            classify(fwdAvgMs = 15f, revAvgMs = 15f, bestOffsetMs = 2f,
                senderNtpSynced = true, reflectorSynced = true))
    }

    @Test
    fun `only sender NTP-synced does not yield SYNCED`() {
        val status = classify(fwdAvgMs = 15f, revAvgMs = 15f, bestOffsetMs = 2f,
            senderNtpSynced = true, reflectorSynced = false)
        assertEquals(ClockSyncStatus.ESTIMATED, status)
    }

    // ── UNSYNCABLE: null offset ───────────────────────────────────────────

    @Test
    fun `null bestOffset yields UNSYNCABLE`() {
        assertEquals(ClockSyncStatus.UNSYNCABLE,
            classify(fwdAvgMs = 15f, revAvgMs = 15f, bestOffsetMs = null))
    }

    // ── R2-CLOCK-03: negative raw delay → UNSYNCABLE ─────────────────────

    @Test
    fun `R2-CLOCK-03 negative revAvgMs yields UNSYNCABLE`() {
        // Simulates 10s clock skew making revDelayMs negative
        assertEquals(ClockSyncStatus.UNSYNCABLE,
            classify(fwdAvgMs = 10200f, revAvgMs = -9800f, bestOffsetMs = 10000f))
    }

    @Test
    fun `negative fwdAvgMs yields UNSYNCABLE`() {
        assertEquals(ClockSyncStatus.UNSYNCABLE,
            classify(fwdAvgMs = -10f, revAvgMs = 15f, bestOffsetMs = 5f))
    }

    // ── UNSYNCABLE: offset exceeds RTT ────────────────────────────────────

    @Test
    fun `offset larger than RTT yields UNSYNCABLE`() {
        // RTT = 20ms but offset = 25ms → cannot be right
        assertEquals(ClockSyncStatus.UNSYNCABLE,
            classify(fwdAvgMs = 15f, revAvgMs = 5f, bestOffsetMs = 25f))
    }

    // ── R2-CLOCK-02: ESTIMATED with reasonable offset ────────────────────

    @Test
    fun `R2-CLOCK-02 100ms skew on 400ms RTT yields ESTIMATED`() {
        // Actual: 200ms each way + 100ms skew
        // fwdAvg = 300ms, revAvg = 100ms, RTT = 400ms, offset = 100ms
        assertEquals(ClockSyncStatus.ESTIMATED,
            classify(fwdAvgMs = 300f, revAvgMs = 100f, bestOffsetMs = 100f))
    }

    @Test
    fun `zero offset on symmetric path yields ESTIMATED`() {
        assertEquals(ClockSyncStatus.ESTIMATED,
            classify(fwdAvgMs = 15f, revAvgMs = 15f, bestOffsetMs = 0f))
    }

    @Test
    fun `offset exactly equal to RTT yields UNSYNCABLE`() {
        // edge case: offset = 30ms, RTT = 30ms → UNSYNCABLE (not strictly less-than)
        assertEquals(ClockSyncStatus.UNSYNCABLE,
            classify(fwdAvgMs = 20f, revAvgMs = 10f, bestOffsetMs = 30f))
    }
}
