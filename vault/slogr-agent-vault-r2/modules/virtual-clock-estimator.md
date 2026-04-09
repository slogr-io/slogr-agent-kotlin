# Virtual Clock Estimator

**Status:** Locked
**New module in R2**

---

## Purpose

Estimate clock offset between sender and each reflector using TWAMP timestamps, enabling one-way delay measurement without NTP sync. Inspired by Cisco Accedian "Remote Clock Detection."

## Background

TWAMP provides four timestamps per packet:
- T1: sender sends (sender clock)
- T2: reflector receives (reflector clock)
- T3: reflector sends (reflector clock)
- T4: sender receives (sender clock)

RTT = `(T4 - T1) - (T3 - T2)` — always accurate, no cross-clock math.
Forward delay = `T2 - T1` — requires clock sync.
Reverse delay = `T4 - T3` — requires clock sync.
One-way jitter (IPDV) = variation between consecutive one-way delays — always accurate (clock offset cancels).

## Algorithm

### Per-Packet Offset Estimation

For each TWAMP packet in a session:

```kotlin
val rawOffset = ((t2 - t1) + (t3 - t4)) / 2.0  // same formula as NTP
```

This is the estimated clock difference between sender and reflector. Accuracy depends on path symmetry (same delay in both directions).

### Best Offset Selection (Per Session)

Over N packets in a measurement window (typically 100):

```kotlin
// The packet with the minimum RTT is closest to the "true" delay
// (least affected by queuing/congestion), so its offset estimate is most accurate
val bestOffset = packets
    .sortedBy { it.rtt }
    .take(5)                    // take 5 lowest-RTT samples
    .map { it.rawOffset }
    .median()                   // median of top-5 for robustness
```

### Sync Detection

After computing bestOffset, check if the one-way delays make sense:

```kotlin
val correctedFwd = (t2 - t1) - bestOffset
val correctedRev = (t4 - t3) + bestOffset
val sumOfOneWay = correctedFwd + correctedRev

val syncStatus = when {
    // Both endpoints have NTP and Error Estimate reports "synchronized"
    senderNtpSynced && reflectorErrorEstimate.isSynchronized -> SYNCED

    // Sum of corrected one-way delays is within 10% of RTT
    abs(sumOfOneWay - rtt) < rtt * 0.10 -> ESTIMATED

    // Sum diverges wildly (one is negative, or sum >> RTT)
    else -> UNSYNCABLE
}
```

### Ground-Truth RTT

Per-packet RTT is always computed from same-clock timestamp pairs:

```
rtt = (T4 - T1) - (T3 - T2)
       sender clock    reflector clock
```

This is **always clock-independent** and stored as `rtt_min_ms`, `rtt_avg_ms`, `rtt_max_ms`.

### Applying the Offset (Directional Split)

The offset-corrected one-way delays provide only the **ratio** (what % is forward vs reverse).
The actual values are scaled so that `fwd + rev == ground-truth RTT` exactly.

```kotlin
// Ground truth per packet (always accurate)
val rtt = ntpDiffMs(rxNtp, txNtp) - (reflectorProcNs / 1_000_000)   // (T4-T1) - (T3-T2)

when (syncStatus) {
    SYNCED, ESTIMATED -> {
        // Offset-corrected estimates (used only for ratio)
        val estFwd = (rawFwd - offset).coerceAtLeast(0f)
        val estRev = (rawRev + offset).coerceAtLeast(0f)
        // Scale to ground truth
        val fwdRatio = estFwd / (estFwd + estRev)
        fwdDelayMs = rtt * fwdRatio
        revDelayMs = rtt - fwdDelayMs            // guarantees fwd + rev == rtt
    }
    UNSYNCABLE -> {
        // No usable directional signal — fwd/rev set to 0 (unavailable)
        // rttAvgMs still has the correct ground-truth value
        fwdDelayMs = 0f
        revDelayMs = 0f
    }
}
```

### SLA Grading

The SLA evaluator compares `rttAvgMs` (ground-truth round-trip) against profile thresholds,
not `fwdAvgRttMs` (one-way). Jitter uses `max(fwd, rev)`.

## Data Model Changes

### MeasurementResult (extends R1)

```kotlin
data class MeasurementResult(
    // ... existing R1 fields ...

    // Ground-truth RTT: (T4-T1) - (T3-T2), always clock-independent
    val rttMinMs: Float,
    val rttAvgMs: Float,
    val rttMaxMs: Float,

    // R2 additions:
    val clockSyncStatus: ClockSyncStatus,       // SYNCED, ESTIMATED, UNSYNCABLE
    val estimatedClockOffsetMs: Float? = null,   // null if UNSYNCABLE
)

enum class ClockSyncStatus {
    SYNCED,       // Both NTP-synced, raw T2-T1/T4-T3 used
    ESTIMATED,    // Virtual clock offset estimated, ratio-scaled to RTT
    UNSYNCABLE    // Can't determine offset, fwd/rev are 0 (unavailable)
}
```

### OTLP Metrics (extends R1)

```
slogr.network.rtt.min              gauge, unit: ms  (ground-truth RTT min)
slogr.network.rtt.avg              gauge, unit: ms  (ground-truth RTT avg)
slogr.network.rtt.max              gauge, unit: ms  (ground-truth RTT max)
slogr.network.clock.sync_status    gauge, unit: enum (0=SYNCED, 1=ESTIMATED, 2=UNSYNCABLE)
slogr.network.clock.offset_ms      gauge, unit: ms (estimated clock offset)
```

### JSON Output (extends R1)

```json
{
  "rtt_avg_ms": 28.5,
  "rtt_min_ms": 25.2,
  "rtt_max_ms": 31.8,
  "clock_sync_status": "ESTIMATED",
  "estimated_clock_offset_ms": 2.3,
  "fwd_avg_rtt_ms": 14.7,
  "rev_avg_rtt_ms": 13.8
}
```

## What Is Always Accurate (Regardless of Sync)

| Metric | Why |
|--------|-----|
| RTT (round-trip) | `(T4-T1) - (T3-T2)` — same clock on each side of subtraction |
| Reflector processing time | `T3 - T2` — same clock |
| One-way jitter (IPDV) | Variation between consecutive packets — clock offset cancels |
| Packet loss | Based on sequence numbers, not timestamps |
| Forward loss | Based on reflector sequence gaps |
| Reverse loss | Based on sender sequence gaps |

## Files

| File | Action |
|------|--------|
| `engine/clock/VirtualClockEstimator.kt` | NEW — offset estimation per responder |
| `engine/clock/ClockSyncDetector.kt` | NEW — SYNCED/ESTIMATED/UNSYNCABLE classification |
| `contracts/src/.../MeasurementResult.kt` | MODIFY — add clockSyncStatus, estimatedClockOffsetMs |
| `engine/assembler/MeasurementAssembler.kt` | MODIFY — apply clock correction before aggregation |
| `platform/otlp/MetricMapper.kt` | MODIFY — add clock sync metrics |
| Tests | Two agents with artificial 100ms clock skew. Verify ESTIMATED status and corrected delays within ±5ms. |
