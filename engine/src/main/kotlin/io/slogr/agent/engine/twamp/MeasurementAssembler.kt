package io.slogr.agent.engine.twamp

import io.slogr.agent.contracts.ClockSyncStatus
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.PacketEntry
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.engine.clock.ClockSyncDetector
import io.slogr.agent.engine.clock.VirtualClockEstimator
import io.slogr.agent.engine.twamp.controller.PacketRecord
import io.slogr.agent.engine.twamp.controller.SenderResult
import io.slogr.agent.engine.twamp.util.TwampTimeUtil
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID
import kotlin.math.sqrt

/**
 * Converts a raw [SenderResult] (per-packet records from [TwampSessionSender])
 * into a [MeasurementResult] suitable for publishing.
 *
 * **RTT ground truth:** Per-packet RTT is always computed as `(T4−T1) − (T3−T2)`
 * using same-clock timestamp pairs, making it independent of clock synchronisation.
 * The directional split (forward vs reverse) is derived from the estimated clock
 * offset ratio, then scaled so that `fwd + rev == RTT` exactly.
 */
object MeasurementAssembler {

    /**
     * Assemble a [MeasurementResult] from the given [result] and session metadata.
     *
     * @param result       Raw sender output (per-packet records + packet counts).
     * @param sessionId    Unique ID for this measurement session.
     * @param pathId       Path identifier shared with the platform layer.
     * @param sourceAgentId This agent's UUID.
     * @param destAgentId  Target agent UUID (or ZERO_UUID if not a Slogr agent).
     * @param srcCloud     Cloud label for this agent (e.g. "aws").
     * @param srcRegion    Region label for this agent (e.g. "us-east-1").
     * @param dstCloud     Cloud label for the target.
     * @param dstRegion    Region label for the target.
     * @param profile      SLA profile used for this session.
     * @param windowTs     Measurement window start timestamp.
     */
    fun assemble(
        result: SenderResult,
        sessionId: UUID,
        pathId: UUID,
        sourceAgentId: UUID,
        destAgentId: UUID,
        srcCloud: String,
        srcRegion: String,
        dstCloud: String,
        dstRegion: String,
        profile: SlaProfile,
        windowTs: Instant = Clock.System.now()
    ): MeasurementResult {
        val packets = result.packets

        // ── Ground-truth RTT per packet: (T4-T1) - (T3-T2) ──────────────────
        // T4-T1 = sender clock only; T3-T2 = reflector clock only (stored as reflectorProcNs).
        // This is always clock-independent.
        val perPacketRtt = packets.map { rec ->
            val totalElapsedMs = TwampTimeUtil.ntpDiffMs(rec.rxNtp, rec.txNtp).toFloat()  // T4-T1
            val reflectorProcMs = rec.reflectorProcNs / 1_000_000f                         // T3-T2
            (totalElapsedMs - reflectorProcMs).coerceAtLeast(0f)
        }

        // Raw cross-clock one-way delays (used only for directional ratio estimation)
        val rawFwdDelays = packets.map { it.fwdDelayMs }
        val rawRevDelays = packets.map { it.revDelayMs }

        // R2: Virtual clock correction — estimate offset, classify sync quality
        val bestOffset  = VirtualClockEstimator.estimate(packets)
        val rawFwdAvg   = rawFwdDelays.averageOrZero()
        val rawRevAvg   = rawRevDelays.averageOrZero()
        val syncStatus  = ClockSyncDetector.classify(
            fwdAvgMs     = rawFwdAvg,
            revAvgMs     = rawRevAvg,
            bestOffsetMs = bestOffset
        )

        // ── Directional split: distribute ground-truth RTT into fwd + rev ────
        // The offset-corrected one-way delays give the best available ratio;
        // we then scale so fwd + rev == RTT exactly (no drift, no rounding gap).
        val (fwdDelays, revDelays) = when (syncStatus) {
            ClockSyncStatus.SYNCED, ClockSyncStatus.ESTIMATED -> {
                val off = bestOffset ?: 0f
                val estFwd = rawFwdDelays.map { (it - off).coerceAtLeast(0f) }
                val estRev = rawRevDelays.map { (it + off).coerceAtLeast(0f) }
                // Scale each packet's fwd/rev so they sum to the ground-truth RTT
                splitByRatio(perPacketRtt, estFwd, estRev)
            }
            ClockSyncStatus.UNSYNCABLE -> {
                // No usable directional signal — report 0/0 so consumers
                // know the split is unavailable; rttAvgMs still has ground truth.
                val zeros = perPacketRtt.map { 0f }
                zeros to zeros
            }
        }

        val rttStats = computeStats(perPacketRtt)
        val fwdStats = computeStats(fwdDelays)
        val revStats = computeStats(revDelays)

        // Jitter (IPDV) is always accurate — clock offset cancels between packets
        val fwdJitter = computeJitter(fwdDelays)
        val revJitter = computeJitter(revDelays)

        val fwdLoss = lossPercent(result.packetsSent, result.packetsRecv)
        val revLoss = fwdLoss  // TWAMP measures RTT; separate fwd/rev loss not directly observable

        val packetEntries = packets.map { rec ->
            PacketEntry(
                seq                 = rec.seq,
                txTimestamp         = ntpToInstant(rec.txNtp),
                rxTimestamp         = ntpToInstant(rec.rxNtp),
                reflectorProcTimeNs = rec.reflectorProcNs,
                fwdDelayMs          = rec.fwdDelayMs,
                revDelayMs          = rec.revDelayMs,
                fwdJitterMs         = null,   // per-packet IPDV not populated here
                revJitterMs         = null,
                txTtl               = rec.txTtl,
                rxTtl               = rec.rxTtl,
                outOfOrder          = rec.outOfOrder
            )
        }

        return MeasurementResult(
            sessionId                = sessionId,
            pathId                   = pathId,
            sourceAgentId            = sourceAgentId,
            destAgentId              = destAgentId,
            srcCloud                 = srcCloud,
            srcRegion                = srcRegion,
            dstCloud                 = dstCloud,
            dstRegion                = dstRegion,
            windowTs                 = windowTs,
            profile                  = profile,
            rttMinMs                 = rttStats.min,
            rttAvgMs                 = rttStats.avg,
            rttMaxMs                 = rttStats.max,
            fwdMinRttMs              = fwdStats.min,
            fwdAvgRttMs              = fwdStats.avg,
            fwdMaxRttMs              = fwdStats.max,
            fwdJitterMs              = fwdJitter,
            fwdLossPct               = fwdLoss,
            revMinRttMs              = revStats.min,
            revAvgRttMs              = revStats.avg,
            revMaxRttMs              = revStats.max,
            revJitterMs              = revJitter,
            revLossPct               = revLoss,
            packetsSent              = result.packetsSent,
            packetsRecv              = result.packetsRecv,
            packets                  = packetEntries,
            clockSyncStatus          = syncStatus,
            estimatedClockOffsetMs   = if (syncStatus != ClockSyncStatus.UNSYNCABLE) bestOffset else null
        )
    }

    // ── Statistics helpers ────────────────────────────────────────────────────

    private data class Stats(val min: Float, val avg: Float, val max: Float)

    private fun computeStats(values: List<Float>): Stats {
        if (values.isEmpty()) return Stats(0f, 0f, 0f)
        val min = values.min()
        val max = values.max()
        val avg = values.average().toFloat()
        return Stats(min, avg, max)
    }

    /**
     * Mean absolute deviation between successive packet delays (IPDV / jitter).
     * Returns 0 when fewer than 2 packets received.
     */
    private fun computeJitter(delays: List<Float>): Float {
        if (delays.size < 2) return 0f
        val diffs = delays.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        return diffs.average().toFloat()
    }

    private fun lossPercent(sent: Int, recv: Int): Float {
        if (sent <= 0) return 0f
        return ((sent - recv).coerceAtLeast(0).toFloat() / sent * 100f)
    }

    /**
     * Distribute [rtt] into forward/reverse per packet using the ratio from
     * [estFwd] and [estRev]. Guarantees `fwd[i] + rev[i] == rtt[i]` exactly.
     */
    private fun splitByRatio(
        rtt: List<Float>,
        estFwd: List<Float>,
        estRev: List<Float>
    ): Pair<List<Float>, List<Float>> {
        val fwd = mutableListOf<Float>()
        val rev = mutableListOf<Float>()
        for (i in rtt.indices) {
            val sum = estFwd[i] + estRev[i]
            if (sum <= 0f) {
                // No usable ratio — split evenly
                fwd.add(rtt[i] / 2f)
                rev.add(rtt[i] / 2f)
            } else {
                val f = rtt[i] * (estFwd[i] / sum)
                fwd.add(f)
                rev.add(rtt[i] - f)   // subtract to avoid floating-point gap
            }
        }
        return fwd to rev
    }

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else average().toFloat()

    private fun ntpToInstant(ntpTimestamp: Long): Instant {
        val millis = TwampTimeUtil.ntpToMillis(ntpTimestamp)
        return Instant.fromEpochMilliseconds(millis)
    }
}
