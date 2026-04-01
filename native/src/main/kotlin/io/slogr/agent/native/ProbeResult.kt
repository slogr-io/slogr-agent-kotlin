package io.slogr.agent.native

/**
 * Result of a single traceroute probe (icmpProbe or udpProbe).
 *
 * @param hopIp  IPv4 address string of the responding router; null on timeout.
 * @param rttMs  Round-trip time in milliseconds; null on timeout.
 * @param reached  True if the probe reached the final destination
 *                 (ICMP ECHO REPLY or PORT_UNREACH), false if TTL expired.
 * @param icmpType  ICMP type of the received response; null on timeout.
 * @param icmpCode  ICMP code of the received response; null on timeout.
 */
data class ProbeResult(
    val hopIp: String?,
    val rttMs: Float?,
    val reached: Boolean,
    val icmpType: Int?,
    val icmpCode: Int?
) {
    val isTimeout: Boolean get() = hopIp == null && rttMs == null

    companion object {
        val TIMEOUT = ProbeResult(
            hopIp = null, rttMs = null, reached = false,
            icmpType = null, icmpCode = null
        )
    }
}
