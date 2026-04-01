# Push Config (R2)

**Status:** Locked
**Extends:** R1 `push_config` command (4 fields → 8 fields)

---

## Payload

```json
{
  "command_type": "push_config",
  "payload": {
    "twamp_packet_count": 100,
    "traceroute_max_hops": 30,
    "traceroute_probes_per_hop": 3,
    "traceroute_heartbeat_interval_cycles": 6,
    "traceroute_forced_refresh_hours": 6,
    "traceroute_fallback_modes": ["ICMP", "TCP", "UDP"],
    "reporting_threshold_ms": 10,
    "buffer_flush_interval_s": 30,
    "wal_max_size_mb": 500,
    "wal_max_age_hours": 72
  }
}
```

## Field Details

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `twamp_packet_count` | Int | 100 | Number of TWAMP packets per session per window |
| `traceroute_max_hops` | Int | 30 | Maximum TTL for traceroute |
| `traceroute_probes_per_hop` | Int | 3 | Number of probes per TTL (R2 addition) |
| `traceroute_heartbeat_interval_cycles` | Int | 6 | Send heartbeat every N unchanged cycles. 6 × 5 min = every 30 min. (R2 addition) |
| `traceroute_forced_refresh_hours` | Int | 6 | Force full traceroute every N hours regardless of change (R2 addition) |
| `traceroute_fallback_modes` | String[] | ["ICMP","TCP","UDP"] | Traceroute mode fallback order (R2 addition) |
| `reporting_threshold_ms` | Int | 10 | Minimum RTT delta to report a change |
| `buffer_flush_interval_s` | Int | 30 | How often to flush WAL buffer to RabbitMQ |
| `wal_max_size_mb` | Int | 500 | Maximum WAL size on disk in MB. Oldest evicted when exceeded. (R2 addition) |
| `wal_max_age_hours` | Int | 72 | Maximum WAL entry age in hours. Expired entries evicted. (R2 addition) |

## Partial Apply Semantics

All fields are optional. The agent applies only the fields present in the payload and keeps existing values for absent fields. This allows the Admin Console to push just `traceroute_heartbeat_interval_cycles: 12` without touching anything else.

```kotlin
fun applyConfig(payload: Map<String, Any>) {
    payload["twamp_packet_count"]?.let { config.twampPacketCount = it as Int }
    payload["traceroute_max_hops"]?.let { config.tracerouteMaxHops = it as Int }
    // ... same pattern for all fields
    config.persist()  // save to disk
}
```

## Files

| File | Action |
|------|--------|
| `platform/commands/PushConfigHandler.kt` | MODIFY — add 4 new fields with partial apply |
| `platform/config/AgentConfig.kt` | MODIFY — add 4 new config properties with defaults |
