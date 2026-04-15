package io.slogr.agent.native

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Development-only [NativeProbeAdapter] backed by Java's [DatagramSocket].
 *
 * Limitations vs [JniProbeAdapter]:
 *  - TTL and TOS are not captured on receive (always 0 in [RecvResult]).
 *  - Traceroute probes are delegated to [tracerouteDelegate]; by default this
 *    is [SystemTracerouteTransport] which invokes the OS `traceroute`/`tracert`
 *    binary. Neither adapter is production-safe.
 *
 * NEVER inject this in production code. Use [JniProbeAdapter] on Linux.
 */
class JavaUdpTransport(
    private val tracerouteDelegate: NativeProbeAdapter = SystemTracerouteTransport()
) : NativeProbeAdapter {

    private val sockets  = ConcurrentHashMap<Int, DatagramSocket>()
    private val fdSource = AtomicInteger(100)

    // ── Socket lifecycle ─────────────────────────────────────────────────

    override fun createSocket(localIp: InetAddress, localPort: Int): Int =
        createSocket(localIp, localPort, reusePort = false)

    override fun createSocket(localIp: InetAddress, localPort: Int, reusePort: Boolean): Int {
        return try {
            val socket = if (reusePort) {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(localIp, localPort))
                }
            } else {
                DatagramSocket(localPort, localIp)
            }
            val fd = fdSource.getAndIncrement()
            sockets[fd] = socket
            fd
        } catch (_: SocketException) {
            -1
        }
    }

    override fun closeSocket(fd: Int) {
        sockets.remove(fd)?.close()
    }

    override fun getLocalPort(fd: Int): Int = sockets[fd]?.localPort ?: 0

    /**
     * TTL capture is not supported via [DatagramSocket]; silently accepted so
     * callers don't need to branch on adapter type.
     */
    override fun setTtlAndCapture(fd: Int, ttl: Int, ipv6: Boolean): Boolean =
        sockets.containsKey(fd)

    /** SO_TIMESTAMPING is not available via [DatagramSocket]; silently accepted. */
    override fun enableTimestamping(fd: Int): Boolean = sockets.containsKey(fd)

    /** TOS/DSCP marking is not applied via JavaUdpTransport.
     *  DatagramSocket.setTrafficClass() causes some ISPs and consumer routers to
     *  drop UDP packets when the TOS byte is explicitly set (even to valid DSCP values).
     *  DSCP marking works reliably only on the JNI transport (raw sockets on Linux).
     *  On the Java fallback (Windows/macOS), leave the OS default — silently accept. */
    override fun setTos(fd: Int, tos: Short, ipv6: Boolean): Boolean =
        sockets.containsKey(fd)

    override fun setTimeout(fd: Int, timeoutMs: Int): Boolean {
        val s = sockets[fd] ?: return false
        s.soTimeout = timeoutMs
        return true
    }

    override fun connectSocket(fd: Int, remoteIp: InetAddress, remotePort: Int): Boolean {
        val s = sockets[fd] ?: return false
        s.connect(remoteIp, remotePort)
        return true
    }

    // ── Packet I/O ───────────────────────────────────────────────────────

    override fun sendPacket(fd: Int, destIp: InetAddress, destPort: Int, data: ByteArray): Int {
        val s = sockets[fd] ?: return -1
        return try {
            s.send(DatagramPacket(data, data.size, destIp, destPort))
            data.size
        } catch (_: Exception) {
            -1
        }
    }

    override fun recvPacket(fd: Int, data: ByteArray): RecvResult {
        val s = sockets[fd] ?: return RecvResult.TIMEOUT
        val pkt = DatagramPacket(data, data.size)
        return try {
            s.receive(pkt)
            RecvResult(
                bytesRead = pkt.length,
                srcIp     = pkt.address,
                srcPort   = pkt.port,
                ttl       = 0,   // not available via DatagramSocket
                tos       = 0
            )
        } catch (_: SocketTimeoutException) {
            RecvResult.TIMEOUT
        }
    }

    // ── Traceroute probes (delegated) ────────────────────────────────────

    override fun icmpProbe(target: InetAddress, ttl: Int, timeoutMs: Int): ProbeResult =
        tracerouteDelegate.icmpProbe(target, ttl, timeoutMs)

    override fun udpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult =
        tracerouteDelegate.udpProbe(target, port, ttl, timeoutMs)

    override fun tcpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult =
        tracerouteDelegate.tcpProbe(target, port, ttl, timeoutMs)

    // ── Test support ─────────────────────────────────────────────────────

    /** Returns the local port the socket was bound to (0 = unknown or closed). */
    internal fun localPort(fd: Int): Int = sockets[fd]?.localPort ?: 0
}
