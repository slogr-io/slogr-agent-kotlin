---
status: locked
version: 1.0
depends-on:
  - architecture/compose-desktop
---

# Local History (SQLite)

## Purpose

Store measurement results locally for 24 hours so ANONYMOUS and REGISTERED users have a queryable history in the desktop app. CONNECTED users also store locally (the SaaS provides unlimited history separately).

## Database

- **Engine:** SQLite via `sqlite-jdbc` (Xerial)
- **Location:** `%APPDATA%\Slogr\history.db` (Windows) or `~/Library/Application Support/Slogr/history.db` (macOS)
- **Size:** ~1-2 MB per day
- **Retention:** 24 hours. Pruned hourly by background coroutine.

## Schema

```sql
CREATE TABLE measurement_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT NOT NULL,
    reflector_id    TEXT NOT NULL,
    reflector_host  TEXT NOT NULL,
    reflector_region TEXT NOT NULL,
    profile         TEXT NOT NULL,
    measured_at     TEXT NOT NULL,           -- ISO 8601 UTC
    fwd_avg_rtt_ms  REAL,
    fwd_min_rtt_ms  REAL,
    fwd_max_rtt_ms  REAL,
    fwd_jitter_ms   REAL,
    fwd_loss_pct    REAL,
    packets_sent    INTEGER,
    packets_recv    INTEGER,
    grade           TEXT,                    -- GREEN, YELLOW, RED
    traceroute_json TEXT,                    -- JSON array of hops (nullable)
    asn_path_json   TEXT                     -- JSON array of ASN numbers (nullable)
);

CREATE INDEX idx_history_measured_at ON measurement_history(measured_at);
CREATE INDEX idx_history_reflector ON measurement_history(reflector_id, measured_at);
```

## Operations

### Write (after each measurement cycle)

```kotlin
suspend fun insertResult(result: MeasurementResult, reflector: Reflector, profile: SlaProfile) {
    // Called on Dispatchers.IO
    // One row per reflector per measurement cycle
}
```

### Read (UI queries)

```kotlin
// Last N results for display in main window
suspend fun getRecentResults(limit: Int = 50): List<HistoryEntry>

// Results for a specific reflector (for detail view)
suspend fun getResultsForReflector(reflectorId: String, limit: Int = 100): List<HistoryEntry>

// Grade distribution over last 24h (for summary)
suspend fun getGradeDistribution(): Map<SlaGrade, Int>
```

### Prune (hourly background job)

```kotlin
suspend fun pruneOlderThan(cutoff: Instant) {
    // DELETE FROM measurement_history WHERE measured_at < ?
}
```

Runs every hour on a background coroutine. Deletes entries older than 24 hours.

## Threading

All SQLite access on `Dispatchers.IO`. Single connection with WAL mode enabled for concurrent reads during writes:

```kotlin
connection.createStatement().execute("PRAGMA journal_mode=WAL")
connection.createStatement().execute("PRAGMA synchronous=NORMAL")
```

## Data Classes

```kotlin
data class HistoryEntry(
    val sessionId: String,
    val reflectorHost: String,
    val reflectorRegion: String,
    val profile: String,
    val measuredAt: Instant,
    val avgRttMs: Float,
    val jitterMs: Float,
    val lossPct: Float,
    val grade: SlaGrade,
    val hops: List<TracerouteHop>?,        // nullable, parsed from JSON
    val asnPath: List<Int>?                 // nullable, parsed from JSON
)
```

## Files

| File | Action |
|------|--------|
| `desktop-core/history/LocalHistoryStore.kt` | NEW — SQLite operations |
| `desktop-core/history/HistoryPruner.kt` | NEW — hourly cleanup coroutine |
| `desktop-core/history/HistoryEntry.kt` | NEW — data class |
