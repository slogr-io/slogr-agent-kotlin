package io.slogr.agent.native

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.net.InetAddress

/**
 * Low-level UDP loopback test using the JNI SlogrNative API directly.
 *
 * Verifies that the JNI C functions for socket creation, TTL/TOS option setting,
 * sendTo, and recvMsg work end-to-end on Linux loopback.
 *
 * Requires: libslogr-native.so built and in SLOGR_NATIVE_DIR or /opt/slogr/lib.
 * Skipped on non-Linux platforms.
 */
@EnabledOnOs(OS.LINUX)
class UdpLoopbackTest {

    private val localhost   = InetAddress.getByName("127.0.0.1")
    private val localhostIp = localhost.address          // [127, 0, 0, 1]
    private val RECV_PORT   = 18741.toShort()

    private var recvFd = -1
    private var sendFd = -1

    @AfterEach
    fun closeSockets() {
        if (recvFd >= 0) { SlogrNative.closeSocket(recvFd); recvFd = -1 }
        if (sendFd >= 0) { SlogrNative.closeSocket(sendFd); sendFd = -1 }
    }

    @Test
    fun `createSocket returns valid file descriptor`() {
        val fd = SlogrNative.createSocket()
        assertTrue(fd >= 0, "createSocket() returned $fd")
        SlogrNative.closeSocket(fd)
    }

    @Test
    fun `bindSocket succeeds on loopback`() {
        val fd = SlogrNative.createSocket()
        assertTrue(fd >= 0)
        val localhostInt = java.nio.ByteBuffer.wrap(localhostIp).int
        val rv = SlogrNative.bindSocket(fd, localhostInt, RECV_PORT.toInt())
        SlogrNative.closeSocket(fd)
        assertEquals(0, rv, "bindSocket returned $rv")
    }

    @Test
    fun `setSocketOption sets TTL and enables RECVTTL RECVTOS`() {
        val fd = SlogrNative.createSocket()
        assertTrue(fd >= 0)
        val rv = SlogrNative.setSocketOption(fd, 255)
        SlogrNative.closeSocket(fd)
        assertEquals(0, rv, "setSocketOption returned $rv")
    }

    @Test
    fun `setSocketTos sets TOS field`() {
        val fd = SlogrNative.createSocket()
        assertTrue(fd >= 0)
        val rv = SlogrNative.setSocketTos(fd, 184.toShort())   // DSCP EF = 0xB8
        SlogrNative.closeSocket(fd)
        assertEquals(0, rv, "setSocketTos returned $rv")
    }

    @Test
    fun `sendTo and recvMsg loopback round-trip with TTL capture`() {
        // ── Receiver setup ──
        recvFd = SlogrNative.createSocket()
        assertTrue(recvFd >= 0)
        val localhostInt = java.nio.ByteBuffer.wrap(localhostIp).int
        assertEquals(0, SlogrNative.bindSocket(recvFd, localhostInt, RECV_PORT.toInt()))
        assertEquals(0, SlogrNative.setSocketOption(recvFd, 64))
        assertEquals(0, SlogrNative.setSocketTimeout(recvFd, 2000))

        // ── Sender setup ──
        sendFd = SlogrNative.createSocket()
        assertTrue(sendFd >= 0)
        assertEquals(0, SlogrNative.setSocketOption(sendFd, 64))

        // ── Send ──
        val payload = "slogr-loopback-test".encodeToByteArray()
        val sent = SlogrNative.sendTo(sendFd, localhostIp, RECV_PORT, payload, payload.size)
        assertEquals(payload.size, sent, "sendTo returned $sent")

        // ── Receive ──
        val buf     = ByteArray(256)
        val ipOut   = IntArray(1)
        val portOut = ShortArray(1)
        val ttlOut  = ShortArray(1)
        val tosOut  = ShortArray(1)

        val ntpOut = LongArray(1); val tsOut = IntArray(1)
        val rv = SlogrNative.recvMsg(recvFd, buf, buf.size, ipOut, portOut, ttlOut, tosOut, ntpOut, tsOut)
        assertTrue(rv > 0, "recvMsg returned $rv")
        assertEquals(payload.size, rv)
        assertEquals("slogr-loopback-test", buf.copyOf(rv).decodeToString())

        // TTL should be 64 (what we sent) — captured via IP_RECVTTL
        assertEquals(64.toShort(), ttlOut[0], "Expected TTL=64, got ${ttlOut[0]}")
    }

    @Test
    fun `recvMsg times out when no data is sent`() {
        recvFd = SlogrNative.createSocket()
        assertTrue(recvFd >= 0)
        val localhostInt = java.nio.ByteBuffer.wrap(localhostIp).int
        SlogrNative.bindSocket(recvFd, localhostInt, 18742)
        SlogrNative.setSocketTimeout(recvFd, 300)

        val buf   = ByteArray(256)
        val empty = IntArray(1); val ep = ShortArray(1); val et = ShortArray(1); val eo = ShortArray(1)
        val ntp   = LongArray(1); val tsSrc = IntArray(1)
        val rv = SlogrNative.recvMsg(recvFd, buf, buf.size, empty, ep, et, eo, ntp, tsSrc)
        assertTrue(rv <= 0, "Expected timeout (<=0), got $rv")
    }
}
