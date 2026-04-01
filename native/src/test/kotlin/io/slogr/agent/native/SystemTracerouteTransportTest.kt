package io.slogr.agent.native

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Tests for [SystemTracerouteTransport] output parsing only.
 * No subprocess is spawned — parsing logic is exercised directly via
 * [SystemTracerouteTransport.parseOutput].
 */
class SystemTracerouteTransportTest {

    private val transport = SystemTracerouteTransport()
    private val loopback = InetAddress.getByName("127.0.0.1")

    // ── parseOutput: Linux traceroute format ─────────────────────────────

    @Test
    fun `parses Linux traceroute hop 3 correctly`() {
        val output = """
            traceroute to 8.8.8.8 (8.8.8.8), 3 hops max, 60 byte packets
             1  192.168.1.1  1.234 ms  1.456 ms  1.678 ms
             2  10.100.0.1  5.234 ms  5.456 ms  5.678 ms
             3  72.14.233.1  14.234 ms  14.456 ms  14.678 ms
        """.trimIndent()

        val result = transport.parseOutput(output, 3, "8.8.8.8", 50f)
        assertEquals("72.14.233.1", result.hopIp)
        assertEquals(14.234f, result.rttMs)
        assertFalse(result.reached)   // 72.14.233.1 != 8.8.8.8
    }

    @Test
    fun `parses Linux traceroute destination reached`() {
        val output = """
            traceroute to 8.8.8.8 (8.8.8.8), 2 hops max, 60 byte packets
             1  192.168.1.1  1.234 ms  1.456 ms  1.678 ms
             2  8.8.8.8  20.123 ms  20.234 ms  20.345 ms
        """.trimIndent()

        val result = transport.parseOutput(output, 2, "8.8.8.8", 50f)
        assertEquals("8.8.8.8", result.hopIp)
        assertTrue(result.reached)
    }

    @Test
    fun `returns TIMEOUT for asterisk-only Linux hop`() {
        val output = """
            traceroute to 8.8.8.8 (8.8.8.8), 3 hops max, 60 byte packets
             1  192.168.1.1  1.234 ms  1.456 ms  1.678 ms
             2  * * *
             3  72.14.233.1  14.234 ms
        """.trimIndent()

        // Hop 2 has no IP — should return TIMEOUT for that hop
        val result = transport.parseOutput(output, 2, "8.8.8.8", 50f)
        assertTrue(result.isTimeout)
    }

    // ── parseOutput: Windows tracert format ──────────────────────────────

    @Test
    fun `parses Windows tracert hop 3 correctly`() {
        val output = """
            Tracing route to 8.8.8.8 over a maximum of 3 hops

              1    <1 ms    <1 ms    <1 ms  192.168.1.1
              2     5 ms     5 ms     5 ms  10.100.0.1
              3    14 ms    13 ms    14 ms  72.14.233.1

            Trace complete.
        """.trimIndent()

        val result = transport.parseOutput(output, 3, "8.8.8.8", 50f)
        assertEquals("72.14.233.1", result.hopIp)
        assertFalse(result.reached)
    }

    @Test
    fun `returns TIMEOUT when hop number not found`() {
        val output = """
            traceroute to 8.8.8.8 (8.8.8.8), 1 hops max, 60 byte packets
             1  192.168.1.1  1.234 ms
        """.trimIndent()

        val result = transport.parseOutput(output, 5, "8.8.8.8", 50f)
        assertTrue(result.isTimeout)
    }

    @Test
    fun `returns TIMEOUT for empty output`() {
        val result = transport.parseOutput("", 1, "8.8.8.8", 50f)
        assertTrue(result.isTimeout)
    }

    // ── Stub operations return expected defaults ─────────────────────────

    @Test
    fun `createSocket always returns -1`() {
        assertEquals(-1, transport.createSocket(loopback, 0))
    }

    @Test
    fun `recvPacket always returns TIMEOUT`() {
        assertTrue(transport.recvPacket(-1, ByteArray(10)).isTimeout)
    }

    @Test
    fun `sendPacket always returns -1`() {
        assertEquals(-1, transport.sendPacket(-1, loopback, 0, ByteArray(4)))
    }
}
