package io.slogr.agent.native

import java.net.InetAddress

/** Whether the T2 receive timestamp was captured by the kernel or userspace. */
enum class TimestampSource { KERNEL, USERSPACE }

/**
 * Result of a raw UDP receive operation (recvPacket).
 * TTL and TOS are populated only when the JNI adapter is in use;
 * the JavaUdpTransport fallback always returns 0 for both.
 *
 * [kernelTimestampNtp] holds the SO_TIMESTAMPING kernel-captured T2 timestamp
 * in NTP 64-bit format (seconds since 1900 in high 32 bits, fraction in low 32 bits).
 * 0L means the kernel timestamp is unavailable; callers should fall back to
 * [io.slogr.agent.engine.twamp.util.TwampTimeUtil.currentNtpTimestamp].
 */
data class RecvResult(
    val bytesRead: Int,
    val srcIp: InetAddress?,
    val srcPort: Int,
    val ttl: Short,
    val tos: Short,
    /** Kernel-level T2 timestamp (NTP 64-bit). 0L = unavailable (use userspace clock). */
    val kernelTimestampNtp: Long = 0L,
    /** Whether [kernelTimestampNtp] was supplied by the kernel (SO_TIMESTAMPING) or userspace. */
    val timestampSource: TimestampSource = TimestampSource.USERSPACE
) {
    val isTimeout: Boolean get() = bytesRead <= 0

    companion object {
        val TIMEOUT = RecvResult(bytesRead = -1, srcIp = null, srcPort = 0, ttl = 0, tos = 0)
    }
}
