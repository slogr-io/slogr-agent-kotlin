# Egress Optimization — Traceroute Publishing Strategy

**Status:** Locked
**Extends:** R1 `modules/traceroute-asn-pathchange-sla.md`

---

## Problem

200+ agents × 200+ paths × every 5 minutes = 40,000+ traceroute snapshots per window. Each snapshot is ~500B-2KB. At full volume: ~11.5 GB/day. Most is redundant — routing doesn't change every 5 minutes.

## Three-Tier Publishing Strategy

### Tier 1: Change-Only (Default)

- Agent runs traceroute every `traceroute.interval_seconds` (default: 300 = 5 min)
- Compares current `asn_path` (deduplicated ordered ASN list) to previous snapshot's `asn_path`
- If DIFFERENT → publish with `is_heartbeat=false`, full hop details, `changed_hops` and `primary_asn_change` populated
- If SAME → do NOT publish. Save egress.

### Tier 2: Periodic Heartbeat

- Every `traceroute.heartbeat_interval_cycles` unchanged cycles (default: 6 = every 30 min), publish a heartbeat
- `is_heartbeat=true`, `changed_hops=[]`, `primary_asn_change=null`
- Tells Layer 2: "Agent is alive, traceroute is running, path hasn't changed"
- Without heartbeats, Layer 2 cannot distinguish "stable path" from "broken agent"

### Tier 3: Forced Refresh

- Every `traceroute.forced_refresh_hours` hours (default: 6), run full traceroute and publish regardless
- `is_forced_refresh=true`
- Compare to last non-heartbeat snapshot:
  - If hops differ → real path change: `is_heartbeat=false`, `is_forced_refresh=true`
  - If hops same → heartbeat: `is_heartbeat=true`, `is_forced_refresh=true`
- Prevents stale baseline from persisting indefinitely

## Egress Math

| Strategy | Snapshots per path per day | Daily volume (40K paths) | Reduction |
|----------|--------------------------|-------------------------|-----------|
| Always publish (5 min) | 288 | ~11.5 GB | Baseline |
| Change-only + heartbeat every 30 min | ~50-57 | ~2.3 GB | **~80%** |

Savings at AWS egress ($0.09/GB): **~$750/month**.

## Layer 2 Interpretation Rules

| Signal | Layer 2 action |
|--------|---------------|
| `is_heartbeat=false`, `changed_hops` populated | Write `path_change_event`. Update `traceroute_raw`. Update `agent_last_seen`. |
| `is_heartbeat=true` | Update `agent_last_seen.last_traceroute_at` only. Do NOT write `path_change_event`. |
| `is_forced_refresh=true`, `is_heartbeat=false` | Treat as real path change. Write `path_change_event`. |
| `is_forced_refresh=true`, `is_heartbeat=true` | Treat as heartbeat. Update `agent_last_seen` only. |
| No traceroute received for >35 minutes | Alert: agent may be offline or traceroute engine broken. Cross-reference with `agent_health` signal — if health signals are arriving but traceroute is missing, the traceroute engine is broken, not the agent. |

## Configurable Parameters

All tunable via `push_config` command:

```yaml
traceroute:
  interval_seconds: 300
  heartbeat_interval_cycles: 6
  forced_refresh_hours: 6
  max_hops: 30
  probes_per_hop: 3
  fallback_modes: [ICMP, TCP, UDP]
```

## Files

| File | Action |
|------|--------|
| `engine/pathchange/PathChangeDetector.kt` | MODIFY — add heartbeat cycle counter, forced refresh timer |
| `platform/config/AgentConfig.kt` | MODIFY — add traceroute config properties |
| `platform/commands/PushConfigHandler.kt` | MODIFY — handle new traceroute config fields |
