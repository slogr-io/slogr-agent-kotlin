package io.slogr.agent.native

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Cross-platform tests for JavaUdpTransport using DatagramSocket on loopback.
 * These tests run on Windows and Linux (no JNI required).
 */
class JavaUdpTransportTest {

    private val localhost = InetAddress.getByName("127.0.0.1")
    private val transport = JavaUdpTransport(tracerouteDelegate = mockk(relaxed = true))

    private val openFds = mutableListOf<Int>()

    private fun openSocket(port: Int = 0): Int {
        val fd = transport.createSocket(localhost, port)
        if (fd >= 0) openFds.add(fd)
        return fd
    }

    @AfterEach
    fun closeSockets() {
        openFds.forEach { transport.closeSocket(it) }
        openFds.clear()
    }

    // ── Socket lifecycle ─────────────────────────────────────────────────

    @Test
    fun `createSocket returns a non-negative fd`() {
        val fd = openSocket()
        assertTrue(fd >= 0, "Expected valid fd, got $fd")
    }

    @Test
    fun `closeSocket releases the socket`() {
        val fd = openSocket()
        transport.closeSocket(fd)
        openFds.remove(fd)
        // After close, a second close should be a no-op (not throw)
        transport.closeSocket(fd)
    }

    @Test
    fun `setTtlAndCapture returns true for open socket`() {
        val fd = openSocket()
        assertTrue(transport.setTtlAndCapture(fd, 255))
    }

    @Test
    fun `setTos returns true for open socket`() {
        val fd = openSocket()
        assertTrue(transport.setTos(fd, 184.toShort()))
    }

    @Test
    fun `setTimeout returns true for open socket`() {
        val fd = openSocket()
        assertTrue(transport.setTimeout(fd, 2000))
    }

    @Test
    fun `enableTimestamping returns true for open socket (no-op on JavaUdpTransport)`() {
        val fd = openSocket()
        assertTrue(transport.enableTimestamping(fd))
    }

    @Test
    fun `enableTimestamping returns false for unknown fd`() {
        assertFalse(transport.enableTimestamping(9999))
    }

    @Test
    fun `localPort returns assigned ephemeral port`() {
        val fd = openSocket(0)
        val port = transport.localPort(fd)
        assertTrue(port in 1..65535, "Expected valid port, got $port")
    }

    // ── Send / Receive loopback ──────────────────────────────────────────

    @Test
    fun `sendPacket and recvPacket round-trip on loopback`() {
        val recvFd = openSocket(0)
        val recvPort = transport.localPort(recvFd)
        transport.setTimeout(recvFd, 2000)

        val sendFd = openSocket()

        val payload = "hello-slogr".encodeToByteArray()
        val sent = transport.sendPacket(sendFd, localhost, recvPort, payload)
        assertTrue(sent > 0)

        val buf    = ByteArray(256)
        val result = transport.recvPacket(recvFd, buf)

        assertFalse(result.isTimeout)
        assertEquals(payload.size, result.bytesRead)
        assertEquals("hello-slogr", buf.copyOf(result.bytesRead).decodeToString())
        assertEquals(localhost, result.srcIp)
    }

    @Test
    fun `recvPacket returns TIMEOUT when no data within timeout`() {
        val fd = openSocket()
        transport.setTimeout(fd, 150)
        val result = transport.recvPacket(fd, ByteArray(256))
        assertTrue(result.isTimeout)
    }

    @Test
    fun `recvPacket on unknown fd returns TIMEOUT`() {
        val result = transport.recvPacket(9999, ByteArray(256))
        assertTrue(result.isTimeout)
    }

    @Test
    fun `sendPacket on unknown fd returns -1`() {
        val sent = transport.sendPacket(9999, localhost, 9999, ByteArray(4))
        assertEquals(-1, sent)
    }

    // ── Traceroute delegation ────────────────────────────────────────────

    @Test
    fun `icmpProbe delegates to tracerouteDelegate`() {
        val mockDelegate = mockk<NativeProbeAdapter> {
            every { icmpProbe(any(), any(), any()) } returns ProbeResult(
                hopIp = "10.0.0.1", rttMs = 5f, reached = false,
                icmpType = 11, icmpCode = 0
            )
        }
        val t = JavaUdpTransport(tracerouteDelegate = mockDelegate)
        val result = t.icmpProbe(localhost, 1, 2000)
        assertEquals("10.0.0.1", result.hopIp)
        verify { mockDelegate.icmpProbe(localhost, 1, 2000) }
    }

    @Test
    fun `udpProbe delegates to tracerouteDelegate`() {
        val mockDelegate = mockk<NativeProbeAdapter> {
            every { udpProbe(any(), any(), any(), any()) } returns ProbeResult.TIMEOUT
        }
        val t = JavaUdpTransport(tracerouteDelegate = mockDelegate)
        val result = t.udpProbe(localhost, 33434, 3, 2000)
        assertTrue(result.isTimeout)
        verify { mockDelegate.udpProbe(localhost, 33434, 3, 2000) }
    }
}
