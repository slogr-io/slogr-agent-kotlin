package io.slogr.agent.platform.otlp

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.platform.output.FallbackBundle
import java.util.UUID

/**
 * Maps [MeasurementBundle] and [FallbackBundle] onto the locked OTLP metric names.
 *
 * Metric names (Rule T7 — locked, must not be changed):
 * ```
 * slogr.network.rtt.min/avg/max  (ground-truth RTT)
 * slogr.network.rtt.forward.min/avg/max
 * slogr.network.rtt.reverse.min/avg/max
 * slogr.network.jitter.forward/reverse
 * slogr.network.loss.forward/reverse
 * slogr.network.packets.sent/received
 * slogr.network.traceroute.hop_count
 * slogr.network.traceroute.path_changed
 * slogr.network.sla.grade
 * slogr.agent.buffer.size
 * slogr.agent.failures.twamp/traceroute/publish
 * ```
 */
object MetricMapper {

    /** Record TWAMP measurement metrics using the supplied [meter]. */
    fun recordTwamp(meter: Meter, bundle: MeasurementBundle, agentId: UUID, profileName: String) {
        val t    = bundle.twamp
        val base = Attributes.builder()
            .put("agent_id", agentId.toString())
            .put("profile", profileName)
            .put("measurement_method", "twamp")
            .build()

        // Ground-truth RTT: (T4-T1) - (T3-T2), always clock-independent
        meter.gaugeBuilder("slogr.network.rtt.min").build().set(t.rttMinMs.toDouble(), base)
        meter.gaugeBuilder("slogr.network.rtt.avg").build().set(t.rttAvgMs.toDouble(), base)
        meter.gaugeBuilder("slogr.network.rtt.max").build().set(t.rttMaxMs.toDouble(), base)

        // Directional split (ratio-scaled from ground-truth RTT)
        meter.gaugeBuilder("slogr.network.rtt.forward.min").build().set(t.fwdMinRttMs.toDouble(), base)
        meter.gaugeBuilder("slogr.network.rtt.forward.avg").build().set(t.fwdAvgRttMs.toDouble(), base)
        meter.gaugeBuilder("slogr.network.rtt.forward.max").build().set(t.fwdMaxRttMs.toDouble(), base)
        meter.gaugeBuilder("slogr.network.jitter.forward").build().set(t.fwdJitterMs.toDouble(), base)
        meter.gaugeBuilder("slogr.network.loss.forward").build().set(t.fwdLossPct.toDouble(), base)

        t.revAvgRttMs?.let { meter.gaugeBuilder("slogr.network.rtt.reverse.avg").build().set(it.toDouble(), base) }
        t.revMinRttMs?.let { meter.gaugeBuilder("slogr.network.rtt.reverse.min").build().set(it.toDouble(), base) }
        t.revMaxRttMs?.let { meter.gaugeBuilder("slogr.network.rtt.reverse.max").build().set(it.toDouble(), base) }
        t.revJitterMs?.let { meter.gaugeBuilder("slogr.network.jitter.reverse").build().set(it.toDouble(), base) }
        t.revLossPct?.let  { meter.gaugeBuilder("slogr.network.loss.reverse").build().set(it.toDouble(), base) }

        meter.gaugeBuilder("slogr.network.packets.sent").build().set(t.packetsSent.toDouble(), base)
        meter.gaugeBuilder("slogr.network.packets.received").build().set(t.packetsRecv.toDouble(), base)
        meter.gaugeBuilder("slogr.network.sla.grade").build().set(bundle.grade.ordinal.toDouble(), base)

        bundle.traceroute?.let { tr ->
            meter.gaugeBuilder("slogr.network.traceroute.hop_count").build()
                .set(tr.hops.size.toDouble(), base)
            meter.gaugeBuilder("slogr.network.traceroute.path_changed").build()
                .set(if (bundle.pathChange != null) 1.0 else 0.0, base)
        }

        // R2: clock sync metrics (0=SYNCED, 1=ESTIMATED, 2=UNSYNCABLE)
        meter.gaugeBuilder("slogr.network.clock.sync_status").build()
            .set(t.clockSyncStatus.ordinal.toDouble(), base)
        t.estimatedClockOffsetMs?.let {
            meter.gaugeBuilder("slogr.network.clock.offset_ms").build()
                .set(it.toDouble(), base)
        }
    }

    /**
     * Record fallback (ICMP/TCP) measurement metrics.
     * [measurement_method] attribute is "icmp" to distinguish from TWAMP.
     */
    fun recordFallback(meter: Meter, bundle: FallbackBundle, agentId: UUID) {
        val ping = bundle.ping
        val base = Attributes.builder()
            .put("agent_id", agentId.toString())
            .put("profile", bundle.profile.name)
            .put("measurement_method", "icmp")
            .build()

        ping.avgRttMs?.let { meter.gaugeBuilder("slogr.network.rtt.forward.avg").build().set(it.toDouble(), base) }
        ping.minRttMs?.let { meter.gaugeBuilder("slogr.network.rtt.forward.min").build().set(it.toDouble(), base) }
        ping.maxRttMs?.let { meter.gaugeBuilder("slogr.network.rtt.forward.max").build().set(it.toDouble(), base) }
        meter.gaugeBuilder("slogr.network.loss.forward").build().set(ping.lossPct.toDouble(), base)
        meter.gaugeBuilder("slogr.network.packets.sent").build().set(ping.sent.toDouble(), base)
        meter.gaugeBuilder("slogr.network.packets.received").build().set(ping.received.toDouble(), base)
        meter.gaugeBuilder("slogr.network.sla.grade").build().set(bundle.grade.ordinal.toDouble(), base)
    }

    /** Grade values: GREEN=0, YELLOW=1, RED=2 — matching [SlaGrade.ordinal]. */
    fun gradeValue(grade: SlaGrade): Int = grade.ordinal
}
