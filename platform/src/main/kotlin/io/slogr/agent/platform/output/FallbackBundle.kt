package io.slogr.agent.platform.output

import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import java.net.InetAddress

/**
 * Results of the non-TWAMP fallback probes used by [CheckCommand] when no
 * TWAMP responder is available on the target port.
 */
data class FallbackBundle(
    val target: InetAddress,
    val ping: IcmpPingProbe.PingStats,
    val tcp: TcpConnectProbe.TcpConnectResult,
    val traceroute: TracerouteResult?,
    val grade: SlaGrade,
    val profile: SlaProfile
)
