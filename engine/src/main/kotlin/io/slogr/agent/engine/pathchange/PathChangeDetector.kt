package io.slogr.agent.engine.pathchange

import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.PathChangeEvent
import io.slogr.agent.contracts.TracerouteHop
import io.slogr.agent.contracts.TracerouteResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Compares each [TracerouteResult] to the stored baseline for the same
 * (pathId, direction) pair and produces an augmented result + optional event.
 *
 * Storage: in-memory [ConcurrentHashMap]. Lost on restart — first traceroute
 * after restart stores the baseline without emitting a change event.
 *
 * ASN path = ordered, deduplicated (consecutive-same collapsed) list of ASNs
 * from non-timeout, non-private hops.  Falls back to IP-based comparison when
 * ASN data is absent.
 */
class PathChangeDetector {

    /** Output of a single [detect] call. */
    data class DetectionResult(
        val traceroute: TracerouteResult,
        val pathChange: PathChangeEvent?
    )

    private data class PathDirectionKey(val pathId: UUID, val direction: Direction)

    private data class Baseline(
        val asnPath: List<Int>,
        val ipPath: List<String>,   // fallback when ASN unavailable
        val hops: List<TracerouteHop>,
        val snapshotId: UUID
    )

    private val baselines = ConcurrentHashMap<PathDirectionKey, Baseline>()

    /**
     * Determine whether the [result]'s hop path matches the stored baseline.
     *
     * - First call for a given (pathId, direction): stores baseline, returns
     *   result unchanged (isHeartbeat=false, no PathChangeEvent).
     * - Same path: returns result with isHeartbeat=true.
     * - Different path: returns result with isHeartbeat=false + PathChangeEvent.
     */
    fun detect(result: TracerouteResult): DetectionResult {
        val key      = PathDirectionKey(result.pathId, result.direction)
        val newAsns  = extractAsnPath(result.hops)
        val newIps   = extractIpPath(result.hops)
        val baseline = baselines[key]

        if (baseline == null) {
            // First run — store baseline, no change event
            baselines[key] = Baseline(
                asnPath    = newAsns,
                ipPath     = newIps,
                hops       = result.hops,
                snapshotId = result.sessionId
            )
            return DetectionResult(traceroute = result, pathChange = null)
        }

        val useAsn   = newAsns.isNotEmpty() && baseline.asnPath.isNotEmpty()
        val pathSame = if (useAsn) newAsns == baseline.asnPath else newIps == baseline.ipPath

        if (pathSame) {
            val heartbeat = result.copy(
                isHeartbeat    = true,
                prevSnapshotId = baseline.snapshotId
            )
            return DetectionResult(traceroute = heartbeat, pathChange = null)
        }

        // Path changed — compute diff and emit event
        val (changedHops, changedTtl, changedAsn, changedAsnName, hopDelta) =
            computeDiff(baseline.hops, result.hops)

        val event = PathChangeEvent(
            pathId              = result.pathId,
            direction           = result.direction,
            prevAsnPath         = baseline.asnPath,
            newAsnPath          = newAsns,
            primaryChangedAsn   = changedAsn,
            primaryChangedAsnName = changedAsnName,
            changedHopTtl       = changedTtl,
            hopDeltaMs          = hopDelta
        )

        val updated = result.copy(
            isHeartbeat      = false,
            prevSnapshotId   = baseline.snapshotId,
            changedHops      = changedHops,
            primaryAsnChange = if (changedAsn != 0) changedAsn else null
        )

        // Update baseline to the new path
        baselines[key] = Baseline(
            asnPath    = newAsns,
            ipPath     = newIps,
            hops       = result.hops,
            snapshotId = result.sessionId
        )

        return DetectionResult(traceroute = updated, pathChange = event)
    }

    /** Clear baseline for testing / reset. */
    fun clear() = baselines.clear()

    // ── Path extraction ───────────────────────────────────────────────────────

    /**
     * Returns deduplicated (consecutive same-ASN collapsed) list of ASNs,
     * excluding null-IP hops.
     */
    internal fun extractAsnPath(hops: List<TracerouteHop>): List<Int> {
        val raw = hops.mapNotNull { if (it.ip != null) it.asn else null }
        return deduplicateConsecutive(raw)
    }

    internal fun extractIpPath(hops: List<TracerouteHop>): List<String> =
        hops.mapNotNull { it.ip }

    private fun <T> deduplicateConsecutive(list: List<T>): List<T> {
        val result = mutableListOf<T>()
        var prev: T? = null
        for (item in list) {
            if (item != prev) {
                result.add(item)
                prev = item
            }
        }
        return result
    }

    // ── Diff calculation ──────────────────────────────────────────────────────

    private data class DiffResult(
        val changedHops: List<Int>,
        val changedTtl: Int,
        val changedAsn: Int,
        val changedAsnName: String,
        val hopDelta: Float
    )

    private fun computeDiff(
        oldHops: List<TracerouteHop>,
        newHops: List<TracerouteHop>
    ): DiffResult {
        val oldByTtl = oldHops.associateBy { it.ttl }
        val newByTtl = newHops.associateBy { it.ttl }
        val allTtls  = (oldByTtl.keys + newByTtl.keys).toSortedSet()

        val changedHops = mutableListOf<Int>()
        var firstChangedTtl  = -1
        var firstChangedAsn  = 0
        var firstChangedName = "Unknown"
        var hopDelta         = 0f

        for (ttl in allTtls) {
            val oldAsn = oldByTtl[ttl]?.asn
            val newAsn = newByTtl[ttl]?.asn
            if (oldAsn != newAsn) {
                changedHops.add(ttl)
                if (firstChangedTtl == -1) {
                    firstChangedTtl  = ttl
                    firstChangedAsn  = newAsn ?: 0
                    firstChangedName = newByTtl[ttl]?.asnName ?: "Unknown"
                    val oldRtt = oldByTtl[ttl]?.rttMs ?: 0f
                    val newRtt = newByTtl[ttl]?.rttMs ?: 0f
                    hopDelta = newRtt - oldRtt
                }
            }
        }

        return DiffResult(
            changedHops   = changedHops,
            changedTtl    = firstChangedTtl.coerceAtLeast(0),
            changedAsn    = firstChangedAsn,
            changedAsnName = firstChangedName,
            hopDelta      = hopDelta
        )
    }
}
