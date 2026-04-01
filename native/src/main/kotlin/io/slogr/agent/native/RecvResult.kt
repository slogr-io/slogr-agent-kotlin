package io.slogr.agent.native

import java.net.InetAddress

/**
 * Result of a raw UDP receive operation (recvPacket).
 * TTL and TOS are populated only when the JNI adapter is in use;
 * the JavaUdpTransport fallback always returns 0 for both.
 */
data class RecvResult(
    val bytesRead: Int,
    val srcIp: InetAddress?,
    val srcPort: Int,
    val ttl: Short,
    val tos: Short
) {
    val isTimeout: Boolean get() = bytesRead <= 0

    companion object {
        val TIMEOUT = RecvResult(bytesRead = -1, srcIp = null, srcPort = 0, ttl = 0, tos = 0)
    }
}
