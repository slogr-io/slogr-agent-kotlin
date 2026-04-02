package io.slogr.agent.engine.clock

import io.slogr.agent.engine.twamp.controller.PacketRecord

/**
 * Estimates the clock offset between a TWAMP sender and reflector using the
 * per-packet timestamps available in [PacketRecord].
 *
 * **Algorithm (per RFC 5357 / Cisco Accedian "Remote Clock Detection"):**
 *
 * For each packet, the raw offset estimate is:
 * ```
 * rawOffset = (fwdDelayMs - revDelayMs) / 2
 * ```
 * This equals the reflector's clock offset relative to the sender when the
 * path is symmetric (same delay each direction). For asymmetric paths the
 * estimate is biased, but the best low-RTT samples (minimum queueing) are
 * the closest to the true offset.
 *
 * The function returns the median raw offset of the 5 packets with the
 * lowest RTT. The median makes the result robust to a single anomalous
 * low-RTT packet.
 *
 * Returns `null` when [packets] is empty.
 */
object VirtualClockEstimator {

    fun estimate(packets: List<PacketRecord>): Float? {
        if (packets.isEmpty()) return null

        // Sort by RTT = fwdDelayMs + revDelayMs (lowest queueing → best offset estimate)
        val sorted = packets.sortedBy { it.fwdDelayMs + it.revDelayMs }
        val topK = sorted.take(TOP_K.coerceAtMost(sorted.size))

        val offsets = topK.map { (it.fwdDelayMs - it.revDelayMs) / 2f }.sorted()
        val mid = offsets.size / 2
        return if (offsets.size % 2 == 0) (offsets[mid - 1] + offsets[mid]) / 2f
               else offsets[mid]
    }

    /** Number of lowest-RTT samples used for the median. */
    internal const val TOP_K = 5
}
