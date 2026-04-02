package io.slogr.agent.engine.pathchange

import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.TracerouteHop
import io.slogr.agent.contracts.TracerouteResult
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * R2-EGRESS-01 through R2-EGRESS-04: Egress optimization — three-tier publishing strategy.
 *
 * R2-EGRESS-01: Stable path over N cycles emits only periodic heartbeats.
 * R2-EGRESS-02: Path change at cycle 3 publishes the change; heartbeats resume after.
 * R2-EGRESS-03: Forced refresh on unchanged path → is_forced_refresh=true, is_heartbeat=true, shouldPublish=true.
 * R2-EGRESS-04: Forced refresh on changed path → is_forced_refresh=true, is_heartbeat=false, changed_hops populated.
 *
 * Note: R2-EGRESS-05 (heartbeat absence detection > 35 min) is a Layer 2 concern — not agent code.
 */
class EgressOptimizationTest {

    private val pathId = UUID.randomUUID()

    // heartbeatIntervalCycles=3 for faster test cycles (publish every 3rd same-path run)
    private val detector = PathChangeDetector(heartbeatIntervalCycles = 3)

    private val stableHops = listOf(
        hop(1, "10.0.0.1", 1f, asn = 1111),
        hop(2, "8.8.8.8",  12f, asn = 15169)
    )
    private val changedHops = listOf(
        hop(1, "10.0.0.1", 1f, asn = 1111),
        hop(2, "1.1.1.1",  11f, asn = 13335)   // ASN changed
    )

    @BeforeEach
    fun reset() = detector.clear()

    // ── R2-EGRESS-01: stable path, suppress non-heartbeat cycles ─────────────

    @Test
    fun `R2-EGRESS-01 first run always publishes baseline (shouldPublish=true)`() {
        val det = detector.detect(makeResult(pathId, stableHops))
        assertTrue(det.shouldPublish)
        assertFalse(det.traceroute.isHeartbeat)
        assertNull(det.pathChange)
    }

    @Test
    fun `R2-EGRESS-01 same path cycles between heartbeats are suppressed`() {
        // Establish baseline
        detector.detect(makeResult(pathId, stableHops))

        // Cycles 1 and 2 (1-based same-path) should be suppressed; cycle 3 is heartbeat
        val c1 = detector.detect(makeResult(pathId, stableHops))
        assertFalse(c1.shouldPublish, "Cycle 1 (of 3) should be suppressed")
        assertTrue(c1.traceroute.isHeartbeat)

        val c2 = detector.detect(makeResult(pathId, stableHops))
        assertFalse(c2.shouldPublish, "Cycle 2 (of 3) should be suppressed")
        assertTrue(c2.traceroute.isHeartbeat)

        val c3 = detector.detect(makeResult(pathId, stableHops))
        assertTrue(c3.shouldPublish, "Cycle 3 (of 3) should be a periodic heartbeat")
        assertTrue(c3.traceroute.isHeartbeat)
        assertNull(c3.pathChange)
    }

    @Test
    fun `R2-EGRESS-01 periodic heartbeat cycle resets counter`() {
        detector.detect(makeResult(pathId, stableHops))   // baseline
        repeat(3) { detector.detect(makeResult(pathId, stableHops)) }  // cycles 1,2,3 (3=heartbeat)

        // After heartbeat, counter resets → cycles 1,2 suppressed, cycle 3 again heartbeat
        val d1 = detector.detect(makeResult(pathId, stableHops))
        assertFalse(d1.shouldPublish)

        val d2 = detector.detect(makeResult(pathId, stableHops))
        assertFalse(d2.shouldPublish)

        val d3 = detector.detect(makeResult(pathId, stableHops))
        assertTrue(d3.shouldPublish)
    }

    // ── R2-EGRESS-02: path change mid-cycle publishes immediately ─────────────

    @Test
    fun `R2-EGRESS-02 path change at cycle 2 of 3 publishes immediately`() {
        detector.detect(makeResult(pathId, stableHops))   // baseline

        val c1 = detector.detect(makeResult(pathId, stableHops))
        assertFalse(c1.shouldPublish)   // suppressed

        // Path changes at cycle 2
        val change = detector.detect(makeResult(pathId, changedHops))
        assertTrue(change.shouldPublish, "Path change must be published immediately")
        assertFalse(change.traceroute.isHeartbeat)
        assertNotNull(change.pathChange)
        assertTrue(change.traceroute.changedHops.isNotEmpty())
    }

    @Test
    fun `R2-EGRESS-02 heartbeat cycles resume after path change`() {
        detector.detect(makeResult(pathId, stableHops))   // baseline
        detector.detect(makeResult(pathId, changedHops))  // change (resets baseline + cycle count)

        // After change, cycle counting restarts: 1,2 suppressed, 3=heartbeat
        val d1 = detector.detect(makeResult(pathId, changedHops))
        assertFalse(d1.shouldPublish)

        val d2 = detector.detect(makeResult(pathId, changedHops))
        assertFalse(d2.shouldPublish)

        val d3 = detector.detect(makeResult(pathId, changedHops))
        assertTrue(d3.shouldPublish)
        assertTrue(d3.traceroute.isHeartbeat)
    }

    // ── R2-EGRESS-03: forced refresh, unchanged path ──────────────────────────

    @Test
    fun `R2-EGRESS-03 forced refresh on unchanged path emits is_forced_refresh=true is_heartbeat=true`() {
        detector.detect(makeResult(pathId, stableHops))   // baseline
        detector.forceNextRefresh(pathId, Direction.UPLINK)

        val det = detector.detect(makeResult(pathId, stableHops))
        assertTrue(det.shouldPublish, "Forced refresh must publish")
        assertTrue(det.traceroute.isHeartbeat, "Path unchanged → isHeartbeat=true")
        assertTrue(det.traceroute.isForcedRefresh, "Must be marked as forced refresh")
        assertNull(det.pathChange)
    }

    @Test
    fun `R2-EGRESS-03 forced refresh flag is consumed after one detect`() {
        detector.detect(makeResult(pathId, stableHops))   // baseline
        detector.forceNextRefresh(pathId, Direction.UPLINK)
        detector.detect(makeResult(pathId, stableHops))   // consumes forced flag

        // Next cycle should be suppressed again (cycle counter resets after forced publish)
        val next = detector.detect(makeResult(pathId, stableHops))
        assertFalse(next.traceroute.isForcedRefresh, "Forced flag must be cleared after use")
        // cycle count reset → first same-path cycle, not heartbeat
        assertFalse(next.shouldPublish)
    }

    // ── R2-EGRESS-04: forced refresh, changed path ───────────────────────────

    @Test
    fun `R2-EGRESS-04 forced refresh on changed path emits is_forced_refresh=true is_heartbeat=false`() {
        detector.detect(makeResult(pathId, stableHops))   // baseline
        detector.forceNextRefresh(pathId, Direction.UPLINK)

        val det = detector.detect(makeResult(pathId, changedHops))
        assertTrue(det.shouldPublish)
        assertFalse(det.traceroute.isHeartbeat, "Path changed → isHeartbeat=false")
        assertTrue(det.traceroute.isForcedRefresh)
        assertNotNull(det.pathChange)
        assertTrue(det.traceroute.changedHops.isNotEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun hop(ttl: Int, ip: String, rttMs: Float, asn: Int? = null) =
        TracerouteHop(ttl = ttl, ip = ip, rttMs = rttMs, asn = asn,
            asnName = if (asn != null) "AS$asn" else null, lossPct = 0f)

    private fun makeResult(pathId: UUID, hops: List<TracerouteHop>) = TracerouteResult(
        sessionId  = UUID.randomUUID(),
        pathId     = pathId,
        direction  = Direction.UPLINK,
        capturedAt = Clock.System.now(),
        hops       = hops
    )
}
