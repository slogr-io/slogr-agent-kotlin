package io.slogr.agent.engine.probe

import io.slogr.agent.contracts.ProbeMode
import io.slogr.agent.native.NativeProbeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Sends N ICMP echo probes with TTL=64 (end-to-end, not traceroute TTL-expiry)
 * to a target and reports summary statistics.
 *
 * Used by [io.slogr.agent.platform.cli.CheckCommand] when no TWAMP responder
 * is found on the target port.
 */
class IcmpPingProbe(private val adapter: NativeProbeAdapter) {

    data class PingStats(
        val resolvedIp: String?,
        val sent: Int,
        val received: Int,
        val minRttMs: Float?,
        val avgRttMs: Float?,
        val maxRttMs: Float?,
        val lossPct: Float
    )

    /**
     * Sends [count] ICMP echo probes to [target] with TTL=64.
     * Each probe waits up to [timeoutMs] for a reply.
     */
    suspend fun ping(
        target: InetAddress,
        count: Int = 5,
        timeoutMs: Int = 2000
    ): PingStats = withContext(Dispatchers.IO) {
        val rtts = mutableListOf<Float>()
        var resolvedIp: String? = null
        var timeouts = 0

        repeat(count) {
            val result = adapter.icmpProbe(target, TTL_ENDPOINT, timeoutMs)
            val rtt = result.rttMs
            if (!result.isTimeout && rtt != null) {
                rtts.add(rtt)
                if (resolvedIp == null) resolvedIp = result.hopIp
            } else {
                timeouts++
            }
        }

        PingStats(
            resolvedIp = resolvedIp ?: target.hostAddress,
            sent       = count,
            received   = rtts.size,
            minRttMs   = rtts.minOrNull(),
            avgRttMs   = if (rtts.isEmpty()) null else rtts.average().toFloat(),
            maxRttMs   = rtts.maxOrNull(),
            lossPct    = timeouts.toFloat() / count * 100f
        )
    }

    companion object {
        /** TTL large enough to reach any public endpoint without expiring en route. */
        const val TTL_ENDPOINT = 64

        /**
         * Classify probe mode from ICMP and TCP availability.
         *
         * @param icmpSuccess true when ICMP loss < 100% (at least one echo reply received)
         * @param tcpSuccess  true when at least one TCP port connected successfully
         */
        fun classify(icmpSuccess: Boolean, tcpSuccess: Boolean): ProbeMode = when {
            icmpSuccess && tcpSuccess   -> ProbeMode.ICMP_AND_TCP
            !icmpSuccess && tcpSuccess  -> ProbeMode.TCP_ONLY
            icmpSuccess && !tcpSuccess  -> ProbeMode.ICMP_ONLY
            else                        -> ProbeMode.BOTH_FAILED
        }
    }
}
