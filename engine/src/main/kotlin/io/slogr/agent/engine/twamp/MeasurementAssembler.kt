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
 * All RTT statistics are computed over the round-trip delay
 * (fwdDelayMs + revDelayMs) per packet.
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

        // Compute per-direction statistics from raw (cross-clock) delays
        val fwdDelays = packets.map { it.fwdDelayMs }
        val revDelays = packets.map { it.revDelayMs }

        val rawFwdStats = computeStats(fwdDelays)
        val rawRevStats = computeStats(revDelays)

        // R2: Virtual clock correction — estimate offset, classify sync quality
        val bestOffset  = VirtualClockEstimator.estimate(packets)
        val syncStatus  = ClockSyncDetector.classify(
            fwdAvgMs     = rawFwdStats.avg,
            revAvgMs     = rawRevStats.avg,
            bestOffsetMs = bestOffset
        )

        // Apply correction per sync state
        val (fwdStats, revStats) = when (syncStatus) {
            ClockSyncStatus.SYNCED -> rawFwdStats to rawRevStats    // NTP-synced: use raw
            ClockSyncStatus.ESTIMATED -> {
                val off = bestOffset ?: 0f
                computeStats(fwdDelays.map { it - off }) to computeStats(revDelays.map { it + off })
            }
            ClockSyncStatus.UNSYNCABLE -> {
                // Cannot determine direction split — use RTT/2 for all
                val halfRtt = (rawFwdStats.avg + rawRevStats.avg) / 2f
                val s = Stats(halfRtt, halfRtt, halfRtt)
                s to s
            }
        }

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

    private fun ntpToInstant(ntpTimestamp: Long): Instant {
        val millis = TwampTimeUtil.ntpToMillis(ntpTimestamp)
        return Instant.fromEpochMilliseconds(millis)
    }
}
