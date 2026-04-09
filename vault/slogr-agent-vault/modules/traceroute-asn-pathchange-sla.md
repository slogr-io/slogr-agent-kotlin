---
status: locked
version: 1.0
depends-on:
  - modules/jni-native
---

# Traceroute

## Overview

Multi-mode traceroute via JNI native probes. No dependency on OS `traceroute` binary.

## Algorithm

1. Try ICMP mode: send ICMP echo probes with TTL 1..maxHops
2. Count resolved hops (non-timeout responses)
3. If > 50% hops are timeouts, try TCP mode (SYN to port 443) with same parameters
4. If TCP also has > 50% timeouts, try UDP mode with same parameters
5. Keep the mode with the most resolved hops
6. Return structured `TracerouteResult`

The three-mode fallback chain (ICMP → TCP/443 → UDP) matches the Python agent's behavior. TCP/443 is critical because many enterprise and cloud networks block ICMP but allow outbound TCP to port 443. Without TCP fallback, traceroute returns mostly `* * *` in those environments.

If caller specifies a mode explicitly (CLI `--trace-mode icmp|tcp|udp`), skip the fallback — use only that mode.

## Parameters

| Parameter | Default | CLI Flag | Configurable via push_config |
|-----------|---------|----------|------------------------------|
| maxHops | 30 | `--max-hops` | Yes |
| probesPerHop | 2 | `--probes` | Yes |
| timeoutMs | 2000 | `--trace-timeout` | Yes |
| mode | auto (ICMP → TCP/443 → UDP) | `--trace-mode` | No |

## Concurrency

Max 4 concurrent traceroutes (semaphore). Each traceroute takes 2-60 seconds depending on path length and timeouts. Running more than 4 simultaneously causes self-congestion and inaccurate results.

## Private IP Filtering

Before ASN lookup, filter out RFC 1918, CGNAT (100.64/10), loopback (127/8), link-local (169.254/16), and multicast (224/4) addresses. These are passed to the result but with `asn = null`.

## Traceroute Reporting Logic (Layer 2 compliance)

The agent does NOT send every traceroute to Layer 2. The rules:

1. **On route change** (`is_heartbeat=false`): Full traceroute with `changed_hops` and `primary_asn_change` populated. Layer 2 writes a `path_change_event`.
2. **Periodic heartbeat** (`is_heartbeat=true`): Same structure but `changed_hops=[]` and `primary_asn_change=0`. Tells Layer 2 "I'm still running, path is the same." Layer 2 updates `agent_last_seen` but does NOT write a `path_change_event`.
3. **Forced refresh** (`is_forced_refresh=true`): Periodically (e.g., every 6 hours), the agent runs a full traceroute regardless of whether the path changed. Compare hops to the last non-heartbeat snapshot:
   - If hops differ: set `is_heartbeat=false`, `is_forced_refresh=true`. Layer 2 treats it as a real path change.
   - If hops are the same: set `is_heartbeat=true`, `is_forced_refresh=true`. Layer 2 treats it as a heartbeat. Prevents stale snapshots from accumulating.
4. **`prev_snapshot_id`**: Set to the `session_id` of the last non-heartbeat traceroute snapshot for this path+direction. Used by Layer 2 to compute diffs.

---
---

# ASN Resolver

## Overview

Local MaxMind GeoLite2-ASN MMDB file lookup. No network queries. Sub-millisecond per lookup.

## Database Management

| Command | Action |
|---------|--------|
| `slogr-agent setup-asn` | Downloads GeoLite2-ASN.mmdb from MaxMind, saves to `$SLOGR_DATA_DIR/GeoLite2-ASN.mmdb` |
| Connected agent registration | Auto-downloads if not present |
| `push_config` with `asn_db_url` | Updates from a Slogr-hosted mirror (avoids MaxMind license key on every agent) |

Default location: `/opt/slogr/data/GeoLite2-ASN.mmdb` (~7 MB)

## Graceful Degradation

If the MMDB file is not present, traceroute works but hops have `asn = null` and `asnName = null`. Path change detection still works using IP-based comparison (less accurate but functional).

Log a warning on startup: `ASN database not found. Traceroute will show IPs only. Run 'slogr-agent setup-asn' to enable ASN enrichment.`

## Lookup Interface

```kotlin
interface AsnResolver {
    fun resolve(ip: InetAddress): AsnInfo?
    fun isAvailable(): Boolean
}

data class AsnInfo(
    val asn: Int,
    val asnName: String,
    val orgName: String?
)
```

Use MaxMind's `com.maxmind.geoip2:geoip2` Java library with `DatabaseReader` in memory-map mode for best performance.

## Staleness Warning

If the MMDB file is older than 90 days, log a warning on startup suggesting re-download. ASN assignments change slowly but do change.

---
---

# Path Change Detector

## Overview

Compares current ASN path to previous for the same session. Flags changes with details about what changed.

## Algorithm

1. Extract ASN path from traceroute hops: ordered list of ASNs, deduplicated (consecutive same-ASN hops collapsed)
2. Compare to stored previous path for this `pathId + direction`
3. If different: emit `PathChangeEvent` with `prevAsnPath`, `newAsnPath`, `primaryChangedAsn`, `changedHopTtl`
4. Store current path as the new baseline

If ASN database is not available, fall back to IP-based path comparison (less accurate — any IP change triggers a "change" even if the ASN is the same).

## Storage

In-memory `ConcurrentHashMap<PathDirectionKey, AsnPath>` keyed by `(pathId, direction)`. Lost on restart — first traceroute after restart always reports as "unknown" (not "changed"). The SaaS handles this via the `is_heartbeat` flag.

---
---

# SLA Evaluator

## Overview

Scores a `MeasurementResult` against its profile's thresholds.

## Scoring Logic

RTT is evaluated against the ground-truth round-trip time `rttAvgMs`, computed as
`(T4-T1) - (T3-T2)` — always clock-independent. Jitter uses the worse of forward/reverse.

```kotlin
fun evaluate(result: MeasurementResult, profile: SlaProfile): SlaGrade {
    val rtt    = result.rttAvgMs                              // ground truth, not one-way
    val jitter = maxOf(result.fwdJitterMs, result.revJitterMs ?: 0f)
    val loss   = result.fwdLossPct

    if (rtt    > profile.rttRedMs)     return RED
    if (jitter > profile.jitterRedMs)  return RED
    if (loss   > profile.lossRedPct)   return RED
    if (rtt    > profile.rttGreenMs)   return YELLOW
    if (jitter > profile.jitterGreenMs) return YELLOW
    if (loss   > profile.lossGreenPct) return YELLOW
    return GREEN
}
```

Any single metric exceeding the red threshold makes the entire result RED. Any single metric between green and red makes it YELLOW. All metrics under green = GREEN.

## Profile Registry

Profiles are loaded from a bundled JSON file at startup. Connected agents can receive updated profiles via `push_config`. The 24 profiles from the Python agent are the starting set. All threshold values are numeric (Float) — no string/int mixing.

```kotlin
object ProfileRegistry {
    fun get(name: String): SlaProfile?
    fun all(): List<SlaProfile>
    fun update(profiles: List<SlaProfile>)    // from push_config
}
```
