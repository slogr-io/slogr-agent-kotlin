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

### Applying the Offset

```kotlin
when (syncStatus) {
    SYNCED -> {
        // Use raw T2-T1 and T4-T3 directly
        fwdDelayMs = (t2 - t1).toMillis()
        revDelayMs = (t4 - t3).toMillis()
    }
    ESTIMATED -> {
        // Apply virtual clock correction
        fwdDelayMs = ((t2 - t1) - bestOffset).toMillis()
        revDelayMs = ((t4 - t3) + bestOffset).toMillis()
    }
    UNSYNCABLE -> {
        // Can't determine direction split — use RTT/2
        fwdDelayMs = rtt / 2.0f
        revDelayMs = rtt / 2.0f
    }
}
```

## Data Model Changes

### MeasurementResult (extends R1)

```kotlin
data class MeasurementResult(
    // ... existing R1 fields ...

    // R2 additions:
    val clockSyncStatus: ClockSyncStatus,       // SYNCED, ESTIMATED, UNSYNCABLE
    val estimatedClockOffsetMs: Float? = null,   // null if UNSYNCABLE
)

enum class ClockSyncStatus {
    SYNCED,       // Both NTP-synced, raw T2-T1/T4-T3 used
    ESTIMATED,    // Virtual clock offset estimated from packet stream
    UNSYNCABLE    // Can't determine offset, fwd/rev are RTT/2
}
```

### OTLP Metrics (extends R1)

```
slogr.network.clock.sync_status    gauge, unit: enum (0=SYNCED, 1=ESTIMATED, 2=UNSYNCABLE)
slogr.network.clock.offset_ms      gauge, unit: ms (estimated clock offset)
```

### JSON Output (extends R1)

```json
{
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
