package io.slogr.agent.native

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.net.InetAddress

/**
 * Tests JniProbeAdapter via the actual libslogr-native.so.
 * Requires Linux with the library built and in SLOGR_NATIVE_DIR or /opt/slogr/lib.
 * Skipped on non-Linux.
 */
@EnabledOnOs(OS.LINUX)
class JniProbeAdapterTest {

    private val adapter = JniProbeAdapter()
    private val localhost = InetAddress.getByName("127.0.0.1")

    private val openFds = mutableListOf<Int>()

    private fun openSocket(port: Int = 0): Int {
        val fd = adapter.createSocket(localhost, port)
        if (fd >= 0) openFds.add(fd)
        return fd
    }

    @AfterEach
    fun closeSockets() {
        openFds.forEach { adapter.closeSocket(it) }
        openFds.clear()
    }

    @Test
    fun `createSocket returns valid fd`() {
        val fd = openSocket()
        assertTrue(fd >= 0, "Expected valid fd, got $fd")
    }

    @Test
    fun `setTtlAndCapture succeeds`() {
        val fd = openSocket()
        assertTrue(adapter.setTtlAndCapture(fd, 255))
    }

    @Test
    fun `setTos succeeds with DSCP value`() {
        val fd = openSocket()
        assertTrue(adapter.setTos(fd, 184.toShort()))  // DSCP 46 = EF = 0xB8
    }

    @Test
    fun `setTimeout succeeds`() {
        val fd = openSocket()
        assertTrue(adapter.setTimeout(fd, 2000))
    }

    @Test
    fun `send and recv loopback packet`() {
        val recvFd = openSocket(18762)
        adapter.setTtlAndCapture(recvFd, 255)
        adapter.setTimeout(recvFd, 2000)

        val sendFd = openSocket()
        adapter.setTtlAndCapture(sendFd, 255)

        val payload = "slogr-jni-test".encodeToByteArray()
        val sent = adapter.sendPacket(sendFd, localhost, 18762, payload)
        assertTrue(sent > 0)

        val buf    = ByteArray(256)
        val result = adapter.recvPacket(recvFd, buf)
        assertFalse(result.isTimeout)
        assertEquals(payload.size, result.bytesRead)
        assertEquals(payload.decodeToString(), buf.copyOf(result.bytesRead).decodeToString())
    }

    @Test
    fun `recvPacket returns TIMEOUT when no data arrives`() {
        val fd = openSocket(18763)
        adapter.setTimeout(fd, 200)
        val result = adapter.recvPacket(fd, ByteArray(256))
        assertTrue(result.isTimeout)
    }
}
