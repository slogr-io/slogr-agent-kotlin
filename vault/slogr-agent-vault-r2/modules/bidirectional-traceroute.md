# Bidirectional Traceroute (Path Symmetry)

**Status:** Locked
**Extends:** R1 `modules/traceroute-asn-pathchange-sla.md`

---

## Requirement

For every TWAMP session between Agent A and Agent B, BOTH agents must run traceroute toward each other:

```
Agent A (us-east-1) ──traceroute──→ Agent B (eu-west-1)    = UPLINK  for path A→B
Agent B (eu-west-1) ──traceroute──→ Agent A (us-east-1)    = UPLINK  for path B→A
                                                             (= DOWNLINK for path A→B)
```

This reveals path asymmetry: traffic from A→B may take a completely different route than B→A. Different transit providers, different peering points, different hop counts. If only one side traces, you miss half the picture.

## How It Works

Each agent independently traces all its session peers. No coordination between agents needed.

```
Agent A's schedule: "measure paths to B, C, D"
Agent A runs:
  - TWAMP to B (sender side)
  - traceroute to B (UPLINK trace for path A→B)

Agent B's schedule: "measure paths to A, C, E"
Agent B runs:
  - TWAMP to A (sender side)
  - traceroute to A (UPLINK trace for path B→A = DOWNLINK for path A→B)
```

**Symmetric scheduling guarantees completeness:** The Layer 3 BFF enforces that when path A→B is created, path B→A is also created (ADR-029). Each agent runs UPLINK traceroutes only. Layer 2 pairs them by matching `path_id` + `direction`.

## What the Agent Publishes

Each traceroute snapshot includes `direction: "UPLINK"`. The agent always traces FROM itself TO the target. It never runs a "reverse" trace — it only knows its own perspective.

```json
{
  "session_id": "...",
  "path_id": "...",
  "direction": "UPLINK",
  "captured_at": "2026-03-30T12:00:00Z",
  "is_heartbeat": false,
  "is_forced_refresh": false,
  "hops": [
    {"hop_ttl": 1, "hop_ip": "10.0.0.1", "hop_asn": null, "hop_asn_name": null, "hop_rtt_ms": 0.4},
    {"hop_ttl": 2, "hop_ip": "72.14.215.85", "hop_asn": 15169, "hop_asn_name": "Google LLC", "hop_rtt_ms": 3.8},
    {"hop_ttl": 3, "hop_ip": "104.18.12.33", "hop_asn": 13335, "hop_asn_name": "Cloudflare", "hop_rtt_ms": 14.1}
  ],
  "asn_path": [null, 15169, 13335],
  "prev_snapshot_id": "...",
  "changed_hops": [],
  "primary_asn_change": null
}
```

The `asn_path` is the deduplicated ordered list of ASNs. Private hops = `null`. Path change detection compares `asn_path` to the previous snapshot.

## Layer 2 Correlation

Layer 2 knows that path A→B and path B→A are a pair (from the `paths` table in Layer 3 Cloud SQL). When presenting path analysis, Layer 2 joins both directions:

- Agent A's UPLINK trace = "how traffic gets FROM A TO B"
- Agent B's UPLINK trace = "how traffic gets FROM B TO A"

The SaaS can then show:
- "Forward path: AS7018 → AS15169 → AS13335 (3 ASNs)"
- "Reverse path: AS13335 → AS3356 → AS7018 (3 ASNs, different transit!)"

This is invisible to the agent — it just runs traceroutes per its schedule.

## No Agent Code Change for Bidirectional Support

The agent already runs traceroutes for all targets in its schedule. If the schedule includes both A→B and B→A (which the BFF guarantees via symmetric scheduling), both agents trace both directions. No special "bidirectional mode" in the agent.

The only requirement: the egress optimization (heartbeat/change-only) must be per-direction. A path change in the A→B direction does NOT trigger a publish for B→A — those are independent traceroutes with independent baselines.

## Files

| File | Action |
|------|--------|
| No agent code changes | Symmetric scheduling is a Layer 3 BFF concern. Agent runs whatever schedule it receives. |
| `engine/pathchange/PathChangeDetector.kt` | VERIFY — confirm that path change baselines are keyed by `(session_id, direction)` not just `session_id`. R1 already has `direction` in the data model. |
