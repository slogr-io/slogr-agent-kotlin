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

class PathChangeDetectorTest {

    private val detector = PathChangeDetector()
    private val pathId   = UUID.randomUUID()

    @BeforeEach
    fun reset() = detector.clear()

    // ── First run ─────────────────────────────────────────────────────────────

    @Test
    fun `first run stores baseline without emitting path change event`() {
        val result = makeResult(pathId, hops = listOf(hop(1, "1.2.3.4", 100f), hop(2, "8.8.8.8", 200f)))
        val det = detector.detect(result)
        assertNull(det.pathChange)
        assertFalse(det.traceroute.isHeartbeat)
        assertNull(det.traceroute.prevSnapshotId)
    }

    // ── Same path → heartbeat ─────────────────────────────────────────────────

    @Test
    fun `same IP path on second run produces heartbeat`() {
        val hops = listOf(hop(1, "1.2.3.4", 100f), hop(2, "8.8.8.8", 200f))
        val first = makeResult(pathId, hops = hops)
        detector.detect(first)

        val second = makeResult(pathId, hops = hops)
        val det = detector.detect(second)
        assertNull(det.pathChange)
        assertTrue(det.traceroute.isHeartbeat)
        assertEquals(first.sessionId, det.traceroute.prevSnapshotId)
    }

    @Test
    fun `same ASN path (different IPs, same ASNs) produces heartbeat`() {
        val hops1 = listOf(hop(1, "1.2.3.4", 100f, asn = 15169), hop(2, "8.8.8.8", 200f, asn = 15169))
        val hops2 = listOf(hop(1, "1.2.3.99", 105f, asn = 15169), hop(2, "8.8.4.4", 200f, asn = 15169))
        val first = makeResult(pathId, hops = hops1)
        detector.detect(first)

        val second = makeResult(pathId, hops = hops2)
        val det = detector.detect(second)
        // ASN path is the same ([15169]) → heartbeat
        assertNull(det.pathChange)
        assertTrue(det.traceroute.isHeartbeat)
    }

    // ── Different path → path change event ───────────────────────────────────

    @Test
    fun `different path emits PathChangeEvent`() {
        val first = makeResult(pathId, hops = listOf(
            hop(1, "10.0.0.1", 5f, asn = 1234),
            hop(2, "8.8.8.8", 15f, asn = 15169)
        ))
        detector.detect(first)

        val second = makeResult(pathId, hops = listOf(
            hop(1, "10.0.0.2", 5f, asn = 9999),
            hop(2, "8.8.8.8", 15f, asn = 15169)
        ))
        val det = detector.detect(second)

        assertNotNull(det.pathChange)
        assertFalse(det.traceroute.isHeartbeat)
        assertEquals(first.sessionId, det.traceroute.prevSnapshotId)
        assertTrue(det.traceroute.changedHops.isNotEmpty())
    }

    @Test
    fun `PathChangeEvent has correct prevAsnPath and newAsnPath`() {
        val first = makeResult(pathId, hops = listOf(
            hop(1, "1.1.1.1", 5f, asn = 13335),
            hop(2, "8.8.8.8", 15f, asn = 15169)
        ))
        detector.detect(first)

        val second = makeResult(pathId, hops = listOf(
            hop(1, "2.2.2.2", 5f, asn = 7922),
            hop(2, "8.8.8.8", 15f, asn = 15169)
        ))
        val event = detector.detect(second).pathChange!!
        assertEquals(listOf(13335, 15169), event.prevAsnPath)
        assertEquals(listOf(7922, 15169), event.newAsnPath)
        assertEquals(1, event.changedHopTtl)
    }

    // ── ASN path extraction ───────────────────────────────────────────────────

    @Test
    fun `extractAsnPath deduplicates consecutive same-ASN hops`() {
        val hops = listOf(
            hop(1, "1.0.0.1", 5f, asn = 100),
            hop(2, "1.0.0.2", 6f, asn = 100),  // consecutive duplicate
            hop(3, "2.0.0.1", 8f, asn = 200),
            hop(4, "3.0.0.1", 12f, asn = 300),
            hop(5, "3.0.0.2", 13f, asn = 300)  // consecutive duplicate
        )
        assertEquals(listOf(100, 200, 300), detector.extractAsnPath(hops))
    }

    @Test
    fun `extractAsnPath skips null-IP hops`() {
        val hops = listOf(
            hop(1, "1.0.0.1", 5f, asn = 100),
            TracerouteHop(ttl = 2),              // timeout — null IP
            hop(3, "2.0.0.1", 10f, asn = 200)
        )
        assertEquals(listOf(100, 200), detector.extractAsnPath(hops))
    }

    // ── Independent paths for different pathIds ───────────────────────────────

    @Test
    fun `each pathId has independent baseline`() {
        val pathA = UUID.randomUUID()
        val pathB = UUID.randomUUID()
        val hops  = listOf(hop(1, "1.2.3.4", 10f))

        // Establish baselines for both paths
        detector.detect(makeResult(pathA, hops = hops))
        detector.detect(makeResult(pathB, hops = hops))

        // Second run for path A should be heartbeat
        val detA = detector.detect(makeResult(pathA, hops = hops))
        assertTrue(detA.traceroute.isHeartbeat)

        // Second run for path B with different hops should be change event
        val detB = detector.detect(makeResult(pathB, hops = listOf(hop(1, "5.5.5.5", 10f))))
        assertNotNull(detB.pathChange)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hop(
        ttl: Int,
        ip: String,
        rttMs: Float,
        asn: Int? = null,
        asnName: String? = if (asn != null) "AS$asn" else null
    ) = TracerouteHop(ttl = ttl, ip = ip, rttMs = rttMs, asn = asn, asnName = asnName, lossPct = 0f)

    private fun makeResult(
        pathId: UUID,
        hops: List<TracerouteHop>,
        direction: Direction = Direction.UPLINK
    ) = TracerouteResult(
        sessionId  = UUID.randomUUID(),
        pathId     = pathId,
        direction  = direction,
        capturedAt = Clock.System.now(),
        hops       = hops
    )
}
