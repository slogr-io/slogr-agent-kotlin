package io.slogr.agent.engine.clock

import io.slogr.agent.contracts.ClockSyncStatus
import kotlin.math.abs

/**
 * Classifies the clock synchronisation quality for a TWAMP session based on
 * the virtual clock offset estimate from [VirtualClockEstimator].
 *
 * **Classification rules (evaluated in order):**
 *
 * 1. **SYNCED** — both sender and reflector are confirmed NTP-synced
 *    (`senderNtpSynced && reflectorSynced`). Raw T2−T1 / T4−T3 are used.
 *
 * 2. **UNSYNCABLE** — any of the following:
 *    - [bestOffsetMs] is `null` (no packets to estimate from)
 *    - Average forward OR reverse delay is negative (clock skew exceeds one-way delay;
 *      physically impossible and indicates wildly broken NTP)
 *    - Absolute offset exceeds the round-trip time (`abs(offset) > fwdAvg + revAvg`);
 *      the estimation cannot be trusted and fallback to RTT/2 is used.
 *
 * 3. **ESTIMATED** — a valid offset was derived; corrected one-way delays are used.
 */
object ClockSyncDetector {

    fun classify(
        fwdAvgMs: Float,
        revAvgMs: Float,
        bestOffsetMs: Float?,
        senderNtpSynced: Boolean = false,
        reflectorSynced: Boolean = false
    ): ClockSyncStatus {
        if (senderNtpSynced && reflectorSynced) return ClockSyncStatus.SYNCED
        if (bestOffsetMs == null) return ClockSyncStatus.UNSYNCABLE
        if (fwdAvgMs < 0f || revAvgMs < 0f) return ClockSyncStatus.UNSYNCABLE
        val rttMs = fwdAvgMs + revAvgMs
        if (abs(bestOffsetMs) >= rttMs) return ClockSyncStatus.UNSYNCABLE
        return ClockSyncStatus.ESTIMATED
    }
}
