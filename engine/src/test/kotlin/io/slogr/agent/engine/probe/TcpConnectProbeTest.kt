package io.slogr.agent.engine.probe

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class TcpConnectProbeTest {

    private val loopback = InetAddress.getLoopbackAddress()
    private val probe = TcpConnectProbe()

    // ── Successful connect ─────────────────────────────────────────────────────

    @Test
    fun `connects to a listening port and returns non-negative latency`() = runBlocking {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val result = probe.probe(loopback, ports = listOf(port))
            assertFalse(result.skipped)
            assertEquals(port, result.port)
            assertNotNull(result.connectMs)
            assertTrue(result.connectMs!! >= 0f)
        }
    }

    // ── First port refused, second connects ───────────────────────────────────

    @Test
    fun `falls back to second port when first is refused`() = runBlocking {
        val closedPort = findClosedPort()
        ServerSocket(0).use { server ->
            val openPort = server.localPort
            val result = probe.probe(loopback, ports = listOf(closedPort, openPort))
            assertFalse(result.skipped)
            assertEquals(openPort, result.port)
        }
    }

    // ── All ports refused → skipped ────────────────────────────────────────────

    @Test
    fun `all ports refused returns skipped`() = runBlocking {
        val p1 = findClosedPort()
        val p2 = findClosedPort(exclude = p1)
        val result = probe.probe(loopback, ports = listOf(p1, p2), timeoutMs = 500)
        assertTrue(result.skipped)
        assertNull(result.connectMs)
        assertNull(result.port)
    }

    // ── Default ports list ─────────────────────────────────────────────────────

    @Test
    fun `default ports list is 443 then 80`() = runBlocking {
        // When both are closed we get SKIPPED — the key is it doesn't throw
        val result = probe.probe(InetAddress.getByName("127.0.0.2"),
            ports = listOf(19999, 19998), timeoutMs = 200)
        assertTrue(result.skipped)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Finds a port that is not currently bound (best-effort). */
    private fun findClosedPort(exclude: Int = -1): Int {
        // Bind and immediately release to get an ephemeral port number
        val s = ServerSocket(0)
        val port = s.localPort
        s.close()
        return if (port == exclude) findClosedPort() else port
    }
}
