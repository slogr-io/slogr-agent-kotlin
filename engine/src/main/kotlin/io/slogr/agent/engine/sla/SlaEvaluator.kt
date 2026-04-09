package io.slogr.agent.engine.sla

import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaGrade.GREEN
import io.slogr.agent.contracts.SlaGrade.RED
import io.slogr.agent.contracts.SlaGrade.YELLOW
import io.slogr.agent.contracts.SlaProfile

/**
 * Scores a [MeasurementResult] against its [SlaProfile]'s thresholds.
 *
 * **RTT** is evaluated against [MeasurementResult.rttAvgMs] — the ground-truth
 * round-trip time computed as `(T4−T1) − (T3−T2)`, always clock-independent.
 *
 * **Jitter** is evaluated as the worse of forward/reverse (either direction
 * experiencing high variability degrades the user experience).
 *
 * Any single metric exceeding the red threshold → RED.
 * Any single metric between green and red → YELLOW.
 * All metrics under green thresholds → GREEN.
 */
object SlaEvaluator {

    fun evaluate(result: MeasurementResult, profile: SlaProfile): SlaGrade {
        val rtt    = result.rttAvgMs
        val jitter = maxOf(result.fwdJitterMs, result.revJitterMs ?: 0f)
        val loss   = result.fwdLossPct

        if (rtt    > profile.rttRedMs)     return RED
        if (jitter > profile.jitterRedMs)  return RED
        if (loss   > profile.lossRedPct)   return RED
        if (rtt    > profile.rttGreenMs)   return YELLOW
        if (jitter > profile.jitterGreenMs) return YELLOW
        if (loss   > profile.lossGreenPct) return YELLOW
        return GREEN
    }
}
