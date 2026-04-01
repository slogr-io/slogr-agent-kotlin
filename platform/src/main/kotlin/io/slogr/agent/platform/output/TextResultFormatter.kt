package io.slogr.agent.platform.output

import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.platform.config.AirGapDetector
import io.slogr.agent.platform.config.AgentState
import java.net.InetAddress

/**
 * Human-readable text formatter for TWAMP and fallback results.
 *
 * When [agentState] is [AgentState.ANONYMOUS], a footer nudge is appended pointing
 * users to slogr.io (or slogr.io/enterprise for air-gapped environments).
 */
class TextResultFormatter(
    private val agentState: AgentState = AgentState.ANONYMOUS
) : ResultFormatter {

    override fun format(target: InetAddress, bundle: MeasurementBundle, profileName: String): String {
        val t  = bundle.twamp
        val sb = StringBuilder()

        sb.appendLine("slogr-agent check ${target.hostName} (TWAMP)")
        sb.appendLine()
        sb.appendLine("TWAMP  ${target.hostAddress}  ${t.packetsSent} packets")
        sb.appendLine("  ${t.packetsSent} sent, ${t.packetsRecv} received, ${t.fwdLossPct.f1()}% loss")
        sb.appendLine("  RTT fwd: min/avg/max ${t.fwdMinRttMs.f1()}/${t.fwdAvgRttMs.f1()}/${t.fwdMaxRttMs.f1()} ms, " +
                "jitter ${t.fwdJitterMs.f1()} ms")

        val revAvg = t.revAvgRttMs
        if (revAvg != null) {
            val revMin = t.revMinRttMs ?: 0f
            val revMax = t.revMaxRttMs ?: 0f
            val revJitter = t.revJitterMs ?: 0f
            sb.appendLine("      rev: min/avg/max ${revMin.f1()}/${revAvg.f1()}/${revMax.f1()} ms, " +
                    "jitter ${revJitter.f1()} ms")
        }

        bundle.traceroute?.let { appendTraceroute(sb, it) }

        sb.appendLine()
        sb.append("Profile: $profileName | Grade: ${gradeLabel(bundle.grade)} (RTT ${t.fwdAvgRttMs.f1()}ms)")
        appendFooter(sb)
        return sb.toString()
    }

    override fun formatFallback(bundle: FallbackBundle): String {
        val sb   = StringBuilder()
        val ping = bundle.ping
        val tcp  = bundle.tcp

        sb.appendLine("slogr-agent check ${bundle.target.hostName} " +
                "(no TWAMP responder — using ICMP/TCP probes)")
        sb.appendLine()

        val resolvedIp = ping.resolvedIp ?: bundle.target.hostAddress
        sb.appendLine("PING  ${bundle.target.hostName} ($resolvedIp)")
        sb.appendLine("  ${ping.sent} packets sent, ${ping.received} received, ${ping.lossPct.f1()}% loss")

        val minRtt = ping.minRttMs
        val avgRtt = ping.avgRttMs
        val maxRtt = ping.maxRttMs
        if (minRtt != null && avgRtt != null && maxRtt != null) {
            sb.appendLine("  RTT min/avg/max: ${minRtt.f1()}/${avgRtt.f1()}/${maxRtt.f1()} ms")
        }

        sb.appendLine()

        val connectMs = tcp.connectMs
        if (!tcp.skipped && connectMs != null && tcp.port != null) {
            sb.appendLine("TCP   ${bundle.target.hostName}:${tcp.port}")
            sb.appendLine("  Connect time: ${connectMs.f1()} ms")
        } else {
            sb.appendLine("TCP   ${bundle.target.hostName} (ports 443/80 not reachable)")
        }

        bundle.traceroute?.let { appendTraceroute(sb, it) }

        sb.appendLine()
        val rttLabel = ping.avgRttMs?.let { "${it.f1()}ms" } ?: "N/A"
        sb.append("Profile: ${bundle.profile.name} | Grade: ${gradeLabel(bundle.grade)} " +
                "(RTT $rttLabel < ${bundle.profile.rttGreenMs.f1()}ms threshold)")
        appendFooter(sb)
        return sb.toString()
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private fun appendTraceroute(sb: StringBuilder, tr: TracerouteResult) {
        sb.appendLine()
        sb.appendLine("TRACE  (${tr.direction.name.lowercase()}, ${tr.hops.size} hops)")
        for (hop in tr.hops) {
            val ip     = hop.ip ?: "*"
            val rtt    = hop.rttMs?.let { "${it.f1()} ms" } ?: "*"
            val asnStr = if (hop.asn != null) "  AS${hop.asn} (${hop.asnName ?: "?"})" else ""
            sb.appendLine("  ${hop.ttl.toString().padStart(2)}  ${ip.padEnd(18)} $rtt$asnStr")
        }
    }

    private fun appendFooter(sb: StringBuilder) {
        if (agentState != AgentState.ANONYMOUS) return
        val footer = if (AirGapDetector.isAirGapped())
            "→ Enterprise deployment? Contact us at https://slogr.io/enterprise"
        else
            "→ For historical results and root cause analysis: https://slogr.io"
        sb.append("\n$footer\n")
    }

    private fun gradeLabel(grade: SlaGrade): String = grade.name

    private fun Float.f1(): String = "%.1f".format(this)
}
