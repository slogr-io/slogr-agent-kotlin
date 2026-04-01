package io.slogr.agent.native

import java.net.InetAddress

/**
 * Development-only [NativeProbeAdapter] that runs the OS `traceroute` (Linux)
 * or `tracert` (Windows) binary via [ProcessBuilder] to obtain per-hop probes.
 *
 * Limitations:
 *  - Subprocess per hop is slow; not suitable for production use.
 *  - UDP socket operations are unsupported (TWAMP not implemented here).
 *  - Requires `traceroute` on Linux or `tracert` on Windows to be on PATH.
 *
 * NEVER inject in production. Use [JniProbeAdapter] on Linux.
 */
open class SystemTracerouteTransport : NativeProbeAdapter {

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    // ── UDP socket stubs — unsupported ───────────────────────────────────

    override fun createSocket(localIp: InetAddress, localPort: Int): Int = -1
    override fun closeSocket(fd: Int) {}
    override fun getLocalPort(fd: Int): Int = 0
    override fun setTtlAndCapture(fd: Int, ttl: Int, ipv6: Boolean): Boolean = false
    override fun setTos(fd: Int, tos: Short, ipv6: Boolean): Boolean = false
    override fun setTimeout(fd: Int, timeoutMs: Int): Boolean = false
    override fun connectSocket(fd: Int, remoteIp: InetAddress, remotePort: Int): Boolean = false
    override fun sendPacket(fd: Int, destIp: InetAddress, destPort: Int, data: ByteArray): Int = -1
    override fun recvPacket(fd: Int, data: ByteArray): RecvResult = RecvResult.TIMEOUT

    // ── Traceroute probes ────────────────────────────────────────────────

    override fun icmpProbe(target: InetAddress, ttl: Int, timeoutMs: Int): ProbeResult =
        runProbe(target, ttl, timeoutMs)

    override fun udpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult =
        runProbe(target, ttl, timeoutMs)

    /**
     * TCP SYN traceroute is not available via `tracert` (Windows) or the
     * system `traceroute` binary without extra flags.  Delegate to the ICMP
     * path so callers get a best-effort result rather than a hard failure.
     * Production code always uses [JniProbeAdapter] which has a real TCP probe.
     */
    override fun tcpProbe(target: InetAddress, port: Int, ttl: Int, timeoutMs: Int): ProbeResult =
        runProbe(target, ttl, timeoutMs)

    /**
     * Run the OS traceroute up to [ttl] hops and parse the [ttl]-th hop.
     * Protected so tests can override for output-parsing unit tests without
     * spawning an actual subprocess.
     */
    protected open fun runProbe(target: InetAddress, ttl: Int, timeoutMs: Int): ProbeResult {
        val targetStr = target.hostAddress
        val perHopMs  = (timeoutMs / ttl).coerceAtLeast(500)

        val cmd = if (isWindows) {
            listOf("tracert", "-d", "-h", ttl.toString(), "-w", perHopMs.toString(), targetStr)
        } else {
            val waitSec = (timeoutMs / 1000).coerceAtLeast(1)
            listOf("traceroute", "-n", "-m", ttl.toString(), "-w", waitSec.toString(), targetStr)
        }

        val startMs = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val elapsedMs = (System.currentTimeMillis() - startMs).toFloat()
            parseOutput(output, ttl, target.hostAddress, elapsedMs)
        } catch (_: Exception) {
            ProbeResult.TIMEOUT
        }
    }

    /**
     * Parse the output of a traceroute/tracert run and extract the [targetTtl]-th hop.
     * Internal so tests can exercise parsing without spawning a subprocess.
     */
    internal fun parseOutput(output: String, targetTtl: Int, targetIp: String, elapsedMs: Float): ProbeResult {
        for (line in output.lines()) {
            val hopIp = extractHopIp(line, targetTtl) ?: continue
            val rttMs = extractRttMs(line) ?: elapsedMs
            val reached = (hopIp == targetIp)
            return ProbeResult(
                hopIp    = hopIp,
                rttMs    = rttMs,
                reached  = reached,
                icmpType = null,
                icmpCode = null
            )
        }
        return ProbeResult.TIMEOUT
    }

    // ── Parsing helpers ──────────────────────────────────────────────────

    private val ipPattern = Regex("""(\d{1,3}(?:\.\d{1,3}){3})""")

    /** Extract the hop IP from a traceroute/tracert output line for [hopNum]. */
    private fun extractHopIp(line: String, hopNum: Int): String? {
        // Both traceroute and tracert start the hop line with the hop number.
        // Match:  "  3   ..." or " 3  ..."
        if (!line.trimStart().startsWith("$hopNum ") &&
            !line.trimStart().startsWith("$hopNum\t")) return null

        // Extract the last IP address on the line (most reliable field position)
        val matches = ipPattern.findAll(line).toList()
        return matches.lastOrNull()?.value
    }

    /** Parse the first RTT value (ms) from the line, e.g. "14.5 ms" or "14 ms". */
    private fun extractRttMs(line: String): Float? {
        val m = Regex("""(\d+\.?\d*)\s+ms""").find(line) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }
}
