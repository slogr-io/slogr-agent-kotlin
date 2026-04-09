# Slogr Agent — v1.0.5 Backlog
Date: April 9 2026
Status: Code complete — commits ee4b9c4, 0041278 on master. 652 tests passing. Pending: L2 schema propagation, Enterprise query updates, release tag.
Branch: master

## Changes

### FEAT-1 [HIGH]: Ground-truth bidirectional RTT

Files:
  engine/.../twamp/MeasurementAssembler.kt
  engine/.../twamp/model/TwampResult.kt
  contracts/.../data/TwampMeasurement.kt

Problem:
  SLA grading compared fwd_avg_rtt_ms (one-way delay)
  against profile thresholds designed for round-trip.
  A 234ms RTT path (Karachi→US-East) graded GREEN
  because fwd_avg_rtt_ms was ~117ms (below 150ms
  threshold). This is incorrect — the path should
  grade YELLOW or RED.

  One-way metrics (fwd/rev) depend on clock
  synchronization between sender and reflector.
  Unsynchronized clocks produce meaningless splits.

Fix:
  Compute ground-truth RTT as (T4-T1) - (T3-T2)
  using same-clock timestamp pairs. Always
  clock-independent. Three new fields:
    rtt_min_ms — minimum RTT across all packets
    rtt_avg_ms — average RTT across all packets
    rtt_max_ms — maximum RTT across all packets

Commit: ee4b9c4

### FEAT-2 [HIGH]: Directional split anchored to RTT

Files:
  engine/.../twamp/MeasurementAssembler.kt

Problem:
  fwd_avg_rtt_ms and rev_avg_rtt_ms were computed
  from cross-clock timestamps. When clocks are
  unsynchronized, these values are meaningless and
  do not sum to the actual RTT.

Fix:
  Ratio-scale fwd and rev so that fwd + rev == rtt
  is guaranteed. UNSYNCABLE mode sets fwd/rev to 0
  (unavailable) instead of fabricating RTT/2.

Commit: ee4b9c4

### FEAT-3 [MEDIUM]: SLA grading uses full RTT

Files:
  engine/.../sla/SlaEvaluator.kt

Problem:
  Evaluator compared fwd_avg_rtt_ms against profile
  thresholds. Thresholds are round-trip values.
  One-way comparison halved the effective threshold.

Fix:
  Evaluator now compares rtt_avg_ms (round-trip)
  against profile thresholds. Jitter evaluated as
  max(fwd, rev) instead of forward-only.

Commit: ee4b9c4

### FIX-3 [LOW]: Stale comment in DaemonCommand.kt

File: platform/.../cli/DaemonCommand.kt line 129

Problem:
  Comment referenced a duplicate engine.start() call
  that was already removed. The comment itself was
  stale (carried forward from v1.0.3/v1.0.4 backlog).

Fix:
  Removed stale comment.

Commit: 0041278

---

## Carried forward from v1.0.4

### FIX-2 [LOW]: SLOGR_SCHEDULE_PATH env var in wrapper script

(Unchanged from v1.0.4 backlog FIX-2)

File: deploy/wrapper.sh

Fix: Check SLOGR_SCHEDULE_PATH env var, pass
--config flag to daemon command.

---

## Migration (L2 / ClickHouse)

### DDL: Add Nullable columns to twamp_raw

```sql
ALTER TABLE slogr.twamp_raw ADD COLUMN rtt_min_ms Nullable(Float32) AFTER rev_loss_pct;
ALTER TABLE slogr.twamp_raw ADD COLUMN rtt_avg_ms Nullable(Float32) AFTER rtt_min_ms;
ALTER TABLE slogr.twamp_raw ADD COLUMN rtt_max_ms Nullable(Float32) AFTER rtt_avg_ms;
```

Columns MUST be Nullable(Float32), NOT Float32 DEFAULT 0.
NULL = "agent didn't send this field" (v1.0.4).
0.0 = "agent measured sub-millisecond RTT" (v1.0.5+).

### View: measurements_unified

```sql
COALESCE(rtt_avg_ms, fwd_avg_rtt_ms) AS primary_rtt_ms
```

No NULLIF needed — NULL rows naturally fall back via COALESCE.

### Go Ingest Bridge

Add 3 optional pointer fields to TWAMPMessage struct:
  RttMinMs  *float64 `json:"rtt_min_ms,omitempty"`
  RttAvgMs  *float64 `json:"rtt_avg_ms,omitempty"`
  RttMaxMs  *float64 `json:"rtt_max_ms,omitempty"`

Update batch INSERT SQL to include 3 new columns.

---

## Cross-Layer Impact

| Layer | Impact | Summary |
|-------|--------|---------|
| L2 Data Platform | DDL + Go bridge + view | ALTER TABLE (3 Nullable columns), measurements_unified COALESCE, Go struct + INSERT |
| L2.5 Admin | None | Admin console does not query twamp measurement data |
| L3 SaaS | Docs + UNSYNCABLE UI | PathExplorer tx_rx_breakdown: detect fwd=rev=0, show "unavailable" |
| Enterprise | DDL + 6 PHP files | Same ALTER TABLE, COALESCE in alertController, analyticController, homeController, sessionController, PathCorrelationService, MeasurementQueryService |

---

## Build Order for v1.0.5

1. FEAT-1 + FEAT-2 + FEAT-3 — single commit (ee4b9c4)
2. FIX-3 — documentation commit (0041278)
3. L2 schema propagation — pending
4. Enterprise query updates — pending
5. Release tag — pending

Run ./gradlew test after each change.
652 tests passing as of 0041278.
