package io.slogr.agent.native

import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Production [NativeProbeAdapter] backed by libslogr-native.so via [SlogrNative].
 *
 * Linux only. Do NOT instantiate on Windows or when CAP_NET_RAW is absent.
 * All JNI-dependent tests must be tagged @EnabledOnOs(OS.LINUX).
 */
class JniProbeAdapter : NativeProbeAdapter {

    init {
        SlogrNative.requireLoaded()
    }

    // ── Socket lifecycle ─────────────────────────────────────────────────

    override fun createSocket(localIp: InetAddress, localPort: Int): Int {
        return if (localIp is Inet6Address) {
            val fd = SlogrNative.createSocket6()
            if (fd < 0) return -1
            val result = SlogrNative.bindSocket6(fd, localIp.address, localPort.toShort())
            if (result != 0) { SlogrNative.closeSocket(fd); return -1 }
            fd
        } else {
            val fd = SlogrNative.createSocket()
            if (fd < 0) return -1
            val ipInt = ByteBuffer.wrap(localIp.address).int
            val result = SlogrNative.bindSocket(fd, ipInt, localPort)
            if (result != 0) { SlogrNative.closeSocket(fd); return -1 }
            fd
        }
    }

    override fun closeSocket(fd: Int) = SlogrNative.closeSocket(fd)

    override fun getLocalPort(fd: Int): Int = SlogrNative.getLocalPort(fd)

    override fun setTtlAndCapture(fd: Int, ttl: Int, ipv6: Boolean): Boolean =
        if (ipv6) SlogrNative.setSocketOption6(fd, ttl) == 0
        else      SlogrNative.setSocketOption(fd, ttl)  == 0

    override fun setTos(fd: Int, tos: Short, ipv6: Boolean): Boolean =
        if (ipv6) SlogrNative.setSocketTos6(fd, tos) == 0
        else      SlogrNative.setSocketTos(fd, tos)  == 0

    override fun setTimeout(fd: Int, timeoutMs: Int): Boolean =
        SlogrNative.setSocketTimeout(fd, timeoutMs) == 0

    override fun connectSocket(fd: Int, remoteIp: InetAddress, remotePort: Int): Boolean =
        SlogrNative.connectSocket(fd, remoteIp.address, remotePort.toShort()) == 0

    override fun enableTimestamping(fd: Int): Boolean =
        SlogrNative.enableTimestamping(fd) == 0

    // ── Packet I/O ───────────────────────────────────────────────────────

    override fun sendPacket(fd: Int, destIp: InetAddress, destPort: Int, data: ByteArray): Int =
        SlogrNative.sendTo(fd, destIp.address, destPort.toShort(), data, data.size)

    override fun recvPacket(fd: Int, data: ByteArray): RecvResult {
        val ipOut    = IntArray(1)
        val portOut  = ShortArray(1)
        val ttlOut   = ShortArray(1)
        val tosOut   = ShortArray(1)
        val ntpTs    = LongArray(1)
        val tsSource = IntArray(1)   // 0 = USERSPACE, 1 = KERNEL

        val rv = SlogrNative.recvMsg(fd, data, data.size, ipOut, portOut, ttlOut, tosOut, ntpTs, tsSource)
        if (rv <= 0) return RecvResult.TIMEOUT

        val ipBytes = ByteBuffer.allocate(4).putInt(ipOut[0]).array()
        return RecvResult(
            bytesRead          = rv,
            srcIp              = InetAddress.getByAddress(ipBytes),
            srcPort            = portOut[0].toInt() and 0xFFFF,
            ttl                = ttlOut[0],
            tos                = tosOut[0],
            kernelTimestampNtp = ntpTs[0],
            timestampSource    = if (tsSource[0] == 1) TimestampSource.KERNEL else TimestampSource.USERSPACE
        )
    }

    // ── Traceroute probes ────────────────────────────────────────────────

    override fun icmpProbe(target: InetAddress, ttl: Int, timeoutMs: Int): ProbeResult {
        val hopIpOut = ByteArray(4)
        val metaOut  = IntArray(3)
        val rttUs    = SlogrNative.icmpProbe(target.address, ttl, timeoutMs, hopIpOut, metaOut)
        return decodeProbeResult(rttUs, hopIpOut, metaOut)
    }

    override fun udpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult {
        val hopIpOut = ByteArray(4)
        val metaOut  = IntArray(3)
        val rttUs    = SlogrNative.udpProbe(target.address, port, ttl, timeoutMs, hopIpOut, metaOut)
        return decodeProbeResult(rttUs, hopIpOut, metaOut)
    }

    override fun tcpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult {
        val hopIpOut = ByteArray(4)
        val metaOut  = IntArray(3)
        val rttUs    = SlogrNative.tcpProbe(target.address, port, ttl, timeoutMs, hopIpOut, metaOut)
        return decodeProbeResult(rttUs, hopIpOut, metaOut)
    }

    private fun decodeProbeResult(rttUs: Long, hopIpOut: ByteArray, metaOut: IntArray): ProbeResult {
        if (rttUs < 0) return ProbeResult.TIMEOUT

        val hopIp = if (hopIpOut.all { it == 0.toByte() }) null
                    else InetAddress.getByAddress(hopIpOut).hostAddress

        return ProbeResult(
            hopIp    = hopIp,
            rttMs    = rttUs / 1000f,
            reached  = metaOut[0] == 1,
            icmpType = metaOut[1].takeIf { it >= 0 },
            icmpCode = metaOut[2].takeIf { it >= 0 }
        )
    }
}
