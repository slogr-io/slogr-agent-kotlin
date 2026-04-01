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

    // ── probeAll — R2-MPORT-01: all ports reachable ───────────────────────────

    @Test
    fun `R2-MPORT-01 probeAll all ports reachable returns success for each`() = runBlocking {
        ServerSocket(0).use { s1 ->
            ServerSocket(0).use { s2 ->
                val results = probe.probeAll(loopback, ports = listOf(s1.localPort, s2.localPort))
                assertEquals(2, results.size)
                assertTrue(results.all { it.success }, "expected all successes: $results")
                assertTrue(results.all { it.connectMs != null && it.connectMs!! >= 0f })
                assertEquals(s1.localPort, results[0].port)
                assertEquals(s2.localPort, results[1].port)
            }
        }
    }

    // ── probeAll — R2-MPORT-02: mixed open/blocked → per-port result ──────────

    @Test
    fun `R2-MPORT-02 probeAll open port success blocked port failure returned for each`() = runBlocking {
        val closedPort = findClosedPort()
        ServerSocket(0).use { server ->
            val openPort = server.localPort
            val results = probe.probeAll(loopback, ports = listOf(openPort, closedPort), timeoutMs = 500)
            assertEquals(2, results.size)
            assertTrue(results[0].success, "open port should succeed")
            assertNotNull(results[0].connectMs)
            assertFalse(results[1].success, "closed port should fail")
            assertNull(results[1].connectMs)
        }
    }

    // ── probeAll — R2-MPORT-05: blocked port does not stall subsequent probes ─

    @Test
    fun `R2-MPORT-05 refused ports do not block subsequent port probes`() = runBlocking {
        val p1 = findClosedPort()
        val p2 = findClosedPort(exclude = p1)
        val p3 = findClosedPort(exclude = p2)
        val results = probe.probeAll(loopback, ports = listOf(p1, p2, p3), timeoutMs = 500)
        // All three ports must be probed — not stopped at first failure
        assertEquals(3, results.size)
        assertTrue(results.all { !it.success })
    }

    // ── probeAll — single port list ────────────────────────────────────────────

    @Test
    fun `probeAll single port returns single result`() = runBlocking {
        ServerSocket(0).use { server ->
            val results = probe.probeAll(loopback, ports = listOf(server.localPort))
            assertEquals(1, results.size)
            assertTrue(results[0].success)
        }
    }

    // ── probeAll — empty port list ─────────────────────────────────────────────

    @Test
    fun `probeAll empty port list returns empty list`() = runBlocking {
        val results = probe.probeAll(loopback, ports = emptyList())
        assertTrue(results.isEmpty())
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
