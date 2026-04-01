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
 * Any single metric exceeding the red threshold → RED.
 * Any single metric between green and red → YELLOW.
 * All metrics under green thresholds → GREEN.
 */
object SlaEvaluator {

    fun evaluate(result: MeasurementResult, profile: SlaProfile): SlaGrade {
        if (result.fwdAvgRttMs > profile.rttRedMs)     return RED
        if (result.fwdJitterMs > profile.jitterRedMs)  return RED
        if (result.fwdLossPct  > profile.lossRedPct)   return RED
        if (result.fwdAvgRttMs > profile.rttGreenMs)   return YELLOW
        if (result.fwdJitterMs > profile.jitterGreenMs) return YELLOW
        if (result.fwdLossPct  > profile.lossGreenPct) return YELLOW
        return GREEN
    }
}
