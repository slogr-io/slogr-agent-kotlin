package io.slogr.agent.engine.traceroute

import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.TracerouteHop
import io.slogr.agent.contracts.TracerouteMode
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.interfaces.AsnResolver
import io.slogr.agent.native.NativeProbeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.net.InetAddress
import java.util.UUID

/**
 * Runs multi-mode traceroute via [NativeProbeAdapter] with ICMP → TCP/443 → UDP fallback.
 *
 * - Max [maxConcurrent] concurrent traceroutes (Semaphore).
 * - Private/reserved IPs are excluded from ASN lookup.
 * - Mode fallback: if > 50 % of hops are timeouts, try the next mode.
 *   The mode with the most resolved hops wins.
 */
class TracerouteOrchestrator(
    private val adapter: NativeProbeAdapter,
    private val asnResolver: AsnResolver,
    private val maxConcurrent: Int = 4
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val probe = NativeTracerouteProbe(adapter)

    suspend fun run(
        target: InetAddress,
        sessionId: UUID,
        pathId: UUID,
        direction: Direction,
        maxHops: Int = 30,
        probesPerHop: Int = 2,
        timeoutMs: Int = 2000,
        mode: TracerouteMode? = null,
        budgetMs: Long = Long.MAX_VALUE
    ): TracerouteResult = semaphore.withPermit {
        val startNs = System.nanoTime()

        val hops = if (mode != null) {
            // Explicit mode — no fallback chain, budget irrelevant
            runMode(target, mode, maxHops, probesPerHop, timeoutMs)
        } else {
            // ICMP → TCP/443 → UDP fallback chain with budget gating
            // First mode (ICMP) always runs unconditionally
            val icmp = runMode(target, TracerouteMode.ICMP, maxHops, probesPerHop, timeoutMs)
            if (!isMostlyStars(icmp)) {
                icmp
            } else {
                // Budget check before TCP
                val remainingBeforeTcp = budgetMs - (System.nanoTime() - startNs) / 1_000_000
                if (remainingBeforeTcp < MIN_USEFUL_BUDGET_MS) {
                    icmp  // best we have
                } else {
                    val tcp = runMode(target, TracerouteMode.TCP, maxHops, probesPerHop, timeoutMs)
                    if (!isMostlyStars(tcp)) {
                        tcp
                    } else {
                        // Budget check before UDP
                        val remainingBeforeUdp = budgetMs - (System.nanoTime() - startNs) / 1_000_000
                        if (remainingBeforeUdp < MIN_USEFUL_BUDGET_MS) {
                            bestOf(icmp, tcp)
                        } else {
                            val udp = runMode(target, TracerouteMode.UDP, maxHops, probesPerHop, timeoutMs)
                            bestOf(icmp, tcp, udp)
                        }
                    }
                }
            }
        }

        val enriched = enrichWithAsn(hops)

        TracerouteResult(
            sessionId  = sessionId,
            pathId     = pathId,
            direction  = direction,
            capturedAt = Clock.System.now(),
            hops       = enriched
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun runMode(
        target: InetAddress,
        mode: TracerouteMode,
        maxHops: Int,
        probesPerHop: Int,
        timeoutMs: Int
    ): List<TracerouteHop> = withContext(Dispatchers.IO) {
        val hops = mutableListOf<TracerouteHop>()
        for (ttl in 1..maxHops) {
            val result = probe.probe(target, ttl, mode, probesPerHop, timeoutMs)
            hops.add(buildHop(ttl, result))
            if (result.reached) break
        }
        hops
    }

    private fun buildHop(ttl: Int, result: NativeTracerouteProbe.HopResult): TracerouteHop {
        val responses = result.probes.filter { !it.isTimeout }
        val ip    = responses.firstOrNull()?.hopIp
        val rttMs = responses.mapNotNull { it.rttMs }.minOrNull()
        val lossPct = (result.probes.size - responses.size).toFloat() / result.probes.size * 100f
        return TracerouteHop(ttl = ttl, ip = ip, rttMs = rttMs, lossPct = lossPct)
    }

    private suspend fun enrichWithAsn(hops: List<TracerouteHop>): List<TracerouteHop> =
        hops.map { hop ->
            val ip = hop.ip ?: return@map hop
            if (isPrivateIp(ip)) return@map hop
            val asn = try {
                asnResolver.resolve(InetAddress.getByName(ip))
            } catch (_: Exception) {
                null
            }
            if (asn != null) hop.copy(asn = asn.asn, asnName = asn.name) else hop
        }

    /** True if more than 50 % of hops are timeouts. */
    private fun isMostlyStars(hops: List<TracerouteHop>): Boolean {
        if (hops.isEmpty()) return true
        return hops.count { it.ip == null } * 2 > hops.size
    }

    private fun bestOf(vararg modes: List<TracerouteHop>): List<TracerouteHop> =
        modes.maxByOrNull { hops -> hops.count { it.ip != null } } ?: emptyList()

    companion object {
        /** Don't start a new fallback mode unless at least this much budget remains. */
        const val MIN_USEFUL_BUDGET_MS = 15_000L
    }

    // ── Private IP detection ──────────────────────────────────────────────────

    /**
     * Returns true for RFC 1918, CGNAT (100.64/10), loopback (127/8),
     * link-local (169.254/16), and multicast (224/4) addresses.
     */
    internal fun isPrivateIp(ipStr: String): Boolean {
        val ip = try {
            InetAddress.getByName(ipStr).address
        } catch (_: Exception) {
            return true
        }
        if (ip.size != 4) return false  // skip IPv6 for now
        val a = ip[0].toInt() and 0xFF
        val b = ip[1].toInt() and 0xFF
        return when {
            a == 10                             -> true  // RFC 1918: 10/8
            a == 172 && b in 16..31             -> true  // RFC 1918: 172.16/12
            a == 192 && b == 168                -> true  // RFC 1918: 192.168/16
            a == 100 && b in 64..127            -> true  // CGNAT: 100.64/10
            a == 127                            -> true  // loopback: 127/8
            a == 169 && b == 254                -> true  // link-local: 169.254/16
            a >= 224                            -> true  // multicast + reserved: 224/4
            else                                -> false
        }
    }
}
