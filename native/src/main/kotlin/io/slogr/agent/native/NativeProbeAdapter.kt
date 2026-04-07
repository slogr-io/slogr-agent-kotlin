package io.slogr.agent.native

import java.net.InetAddress

/**
 * Abstraction over the low-level UDP and traceroute operations needed by the
 * TWAMP engine and TracerouteOrchestrator.
 *
 * Production implementation: [JniProbeAdapter] (Linux, libslogr-native.so).
 * Development fallback:       [JavaUdpTransport] (all platforms, no TTL/TOS capture).
 */
interface NativeProbeAdapter {

    // ── UDP socket lifecycle (TWAMP) ──────────────────────────────────────

    /**
     * Create and bind a UDP socket to [localIp]:[localPort].
     * Returns a non-negative file-descriptor handle on success, -1 on error.
     * Port 0 requests any available ephemeral port.
     */
    fun createSocket(localIp: InetAddress, localPort: Int): Int

    /**
     * Create and bind a UDP socket with optional SO_REUSEPORT.
     * When [reusePort] is true, multiple sockets can bind to the same port
     * (used for fixed TWAMP test port — SLOGR_TEST_PORT).
     */
    fun createSocket(localIp: InetAddress, localPort: Int, reusePort: Boolean): Int =
        createSocket(localIp, localPort)

    fun closeSocket(fd: Int)

    /**
     * Returns the local port the socket [fd] is bound to, or 0 if unknown.
     * Needed to discover the ephemeral port after binding with localPort=0.
     */
    fun getLocalPort(fd: Int): Int

    /**
     * Enable outgoing TTL = [ttl] and ancillary TTL/TOS capture on [fd].
     * Must be called before the first [sendPacket] / [recvPacket] pair.
     */
    fun setTtlAndCapture(fd: Int, ttl: Int, ipv6: Boolean = false): Boolean

    fun setTos(fd: Int, tos: Short, ipv6: Boolean = false): Boolean

    fun setTimeout(fd: Int, timeoutMs: Int): Boolean

    /** Connect [fd] to [remoteIp]:[remotePort] (optional but improves kernel filtering). */
    fun connectSocket(fd: Int, remoteIp: InetAddress, remotePort: Int): Boolean

    /**
     * Enable SO_TIMESTAMPING on [fd] so that [recvPacket] returns a kernel-captured
     * T2 timestamp in [RecvResult.kernelTimestampNtp].
     *
     * Must be called after [setTtlAndCapture]. On platforms without SO_TIMESTAMPING
     * (Windows, macOS, [JavaUdpTransport]) this is a safe no-op that returns true.
     */
    fun enableTimestamping(fd: Int): Boolean

    // ── Packet I/O ───────────────────────────────────────────────────────

    /**
     * Send [data] to [destIp]:[destPort] via [fd].
     * Returns bytes sent (> 0) or -1 on error.
     */
    fun sendPacket(fd: Int, destIp: InetAddress, destPort: Int, data: ByteArray): Int

    /**
     * Receive a UDP datagram into [data] from [fd].
     * Blocks until data arrives or the socket timeout elapses.
     * Returns [RecvResult.TIMEOUT] on timeout.
     */
    fun recvPacket(fd: Int, data: ByteArray): RecvResult

    // ── Traceroute probes ────────────────────────────────────────────────

    /**
     * Send one ICMP ECHO REQUEST with [ttl] to [target] and wait up to
     * [timeoutMs] for an ICMP TIME_EXCEEDED or ECHO_REPLY.
     */
    fun icmpProbe(target: InetAddress, ttl: Int, timeoutMs: Int): ProbeResult

    /**
     * Send one UDP datagram with [ttl] to [target]:[port] and wait up to
     * [timeoutMs] for an ICMP TIME_EXCEEDED or PORT_UNREACH.
     */
    fun udpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult

    /**
     * Send one TCP SYN with [ttl] to [target]:[port] (typically 443) and wait
     * up to [timeoutMs] for ICMP TIME_EXCEEDED (intermediate hop) or a
     * SYN-ACK / RST (destination reached). If a SYN-ACK is received, a RST is
     * sent to close cleanly without completing the handshake.
     */
    fun tcpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult
}
