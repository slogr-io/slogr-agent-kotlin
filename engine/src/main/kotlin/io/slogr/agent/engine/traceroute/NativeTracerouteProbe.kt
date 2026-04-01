package io.slogr.agent.engine.traceroute

import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.native.NativeProbeAdapter
import io.slogr.agent.native.ProbeResult
import java.net.InetAddress

/**
 * Runs a single traceroute hop (one TTL, [probesPerHop] attempts) using [NativeProbeAdapter].
 *
 * Separates "how to probe a single hop" from "how to orchestrate a full traceroute"
 * ([TracerouteOrchestrator]).
 */
internal class NativeTracerouteProbe(private val adapter: NativeProbeAdapter) {

    /** Result of a single hop (one TTL, multiple probes). */
    data class HopResult(
        val ttl: Int,
        val probes: List<ProbeResult>,
        /** True if any probe reached the final destination — traceroute should stop. */
        val reached: Boolean = probes.any { it.reached }
    )

    fun probe(
        target: InetAddress,
        ttl: Int,
        mode: TracerouteMode,
        probesPerHop: Int,
        timeoutMs: Int
    ): HopResult {
        val probes = (1..probesPerHop).map {
            when (mode) {
                TracerouteMode.ICMP -> adapter.icmpProbe(target, ttl, timeoutMs)
                TracerouteMode.TCP  -> adapter.tcpProbe(target, TCP_TRACEROUTE_PORT, ttl, timeoutMs)
                TracerouteMode.UDP  -> adapter.udpProbe(target, UDP_BASE_PORT + ttl, ttl, timeoutMs)
            }
        }
        return HopResult(ttl = ttl, probes = probes)
    }

    companion object {
        /** TCP SYN traceroute targets port 443 — passes most enterprise firewalls. */
        const val TCP_TRACEROUTE_PORT = 443
        /** Classic UDP traceroute base port (RFC 1393). Per-TTL offset avoids collisions. */
        const val UDP_BASE_PORT = 33434
    }
}
