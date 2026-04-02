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
 * R2-BIDIR-01 through R2-BIDIR-03: Bidirectional traceroute — independent baselines per direction.
 *
 * The agent runs UPLINK-only traceroutes. Layer 3 BFF guarantees symmetric scheduling so that
 * each peer also traces back (their UPLINK = this agent's DOWNLINK). No special "bidirectional mode"
 * exists in the agent. The requirement is that path change baselines are keyed by (pathId, direction)
 * so that a change on one direction does NOT affect the other.
 *
 * R2-BIDIR-01: Two agents tracing toward each other use the same path_id with direction=UPLINK.
 * R2-BIDIR-02: Asymmetric paths in each direction are detected independently.
 * R2-BIDIR-03: Path change in one direction only — other direction stays on heartbeat schedule.
 */
class BidirTracerouteTest {

    private val pathId = UUID.randomUUID()
    private val detector = PathChangeDetector(heartbeatIntervalCycles = 1)

    @BeforeEach
    fun reset() = detector.clear()

    // ── R2-BIDIR-01: same path_id, both directions publish as UPLINK ──────────

    @Test
    fun `R2-BIDIR-01 each direction has independent baseline under same pathId`() {
        val uplinkHops   = listOf(hop(1, "10.0.0.1", 1f, asn = 1111), hop(2, "8.8.8.8", 10f, asn = 15169))
        val downlinkHops = listOf(hop(1, "10.0.1.1", 2f, asn = 2222), hop(2, "1.1.1.1",  9f, asn = 13335))

        // First run for each direction stores its own baseline
        val up1   = detector.detect(makeResult(pathId, Direction.UPLINK,   uplinkHops))
        val down1 = detector.detect(makeResult(pathId, Direction.DOWNLINK, downlinkHops))

        assertFalse(up1.traceroute.isHeartbeat)
        assertFalse(down1.traceroute.isHeartbeat)

        // Second run — same hops → heartbeat for each, independently
        val up2   = detector.detect(makeResult(pathId, Direction.UPLINK,   uplinkHops))
        val down2 = detector.detect(makeResult(pathId, Direction.DOWNLINK, downlinkHops))

        assertTrue(up2.traceroute.isHeartbeat)
        assertTrue(down2.traceroute.isHeartbeat)
        assertNull(up2.pathChange)
        assertNull(down2.pathChange)
    }

    @Test
    fun `R2-BIDIR-01 direction field is preserved in result`() {
        val hops = listOf(hop(1, "1.2.3.4", 5f))
        val up   = detector.detect(makeResult(pathId, Direction.UPLINK,   hops))
        val down = detector.detect(makeResult(pathId, Direction.DOWNLINK, hops))

        assertEquals(Direction.UPLINK,   up.traceroute.direction)
        assertEquals(Direction.DOWNLINK, down.traceroute.direction)
    }

    // ── R2-BIDIR-02: asymmetric ASN paths detected per-direction ─────────────

    @Test
    fun `R2-BIDIR-02 different ASN paths per direction are compared independently`() {
        val uplinkPath1   = listOf(hop(1, "10.0.0.1", 1f, asn = 7018),  hop(2, "4.4.4.4", 12f, asn = 7018))
        val uplinkPath2   = listOf(hop(1, "10.0.0.1", 1f, asn = 3356),  hop(2, "4.4.4.4", 13f, asn = 3356))

        val downlinkPath1 = listOf(hop(1, "10.0.1.1", 2f, asn = 13335), hop(2, "1.1.1.1", 9f, asn = 13335))
        val downlinkPath2 = listOf(hop(1, "10.0.1.1", 2f, asn = 174),   hop(2, "1.1.1.1", 9f, asn = 174))

        // Establish baselines
        detector.detect(makeResult(pathId, Direction.UPLINK,   uplinkPath1))
        detector.detect(makeResult(pathId, Direction.DOWNLINK, downlinkPath1))

        // Uplink ASN changes; downlink stays same
        val upChange   = detector.detect(makeResult(pathId, Direction.UPLINK,   uplinkPath2))
        val downNoChange = detector.detect(makeResult(pathId, Direction.DOWNLINK, downlinkPath1))

        assertNotNull(upChange.pathChange,   "Uplink path change should be detected")
        assertNull(downNoChange.pathChange, "Downlink path is unchanged — no event")
        assertTrue(downNoChange.traceroute.isHeartbeat)
    }

    // ── R2-BIDIR-03: change in one direction only ────────────────────────────

    @Test
    fun `R2-BIDIR-03 path change in uplink does not affect downlink baseline`() {
        val sharedInitialHops = listOf(hop(1, "10.0.0.1", 5f, asn = 1234))
        val changedUplinkHops = listOf(hop(1, "10.0.0.2", 5f, asn = 5678))

        // Both directions establish same initial path
        detector.detect(makeResult(pathId, Direction.UPLINK,   sharedInitialHops))
        detector.detect(makeResult(pathId, Direction.DOWNLINK, sharedInitialHops))

        // Only uplink path changes
        val upChange   = detector.detect(makeResult(pathId, Direction.UPLINK,   changedUplinkHops))
        val downSame   = detector.detect(makeResult(pathId, Direction.DOWNLINK, sharedInitialHops))

        assertNotNull(upChange.pathChange,  "Uplink path change should be detected")
        assertTrue(upChange.shouldPublish)
        assertNull(downSame.pathChange,    "Downlink is unchanged — no path change event")
        assertTrue(downSame.traceroute.isHeartbeat)
        // Downlink shouldPublish=true because heartbeatIntervalCycles=1 (always publish)
        assertTrue(downSame.shouldPublish)
    }

    @Test
    fun `R2-BIDIR-03 PathChangeEvent captures direction of changed path`() {
        val hops1 = listOf(hop(1, "1.1.1.1", 5f, asn = 100))
        val hops2 = listOf(hop(1, "2.2.2.2", 5f, asn = 200))

        detector.detect(makeResult(pathId, Direction.UPLINK, hops1))
        val det = detector.detect(makeResult(pathId, Direction.UPLINK, hops2))

        assertEquals(Direction.UPLINK, det.pathChange?.direction)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun hop(ttl: Int, ip: String, rttMs: Float, asn: Int? = null) =
        TracerouteHop(ttl = ttl, ip = ip, rttMs = rttMs, asn = asn,
            asnName = if (asn != null) "AS$asn" else null, lossPct = 0f)

    private fun makeResult(pathId: UUID, direction: Direction, hops: List<TracerouteHop>) =
        TracerouteResult(
            sessionId  = UUID.randomUUID(),
            pathId     = pathId,
            direction  = direction,
            capturedAt = Clock.System.now(),
            hops       = hops
        )
}
