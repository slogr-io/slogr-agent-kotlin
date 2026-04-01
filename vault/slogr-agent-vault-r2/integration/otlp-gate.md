# OTLP Export Gate

**Status:** Locked
**Extends:** R1 `OtlpExporter.kt`

---

## Rule

OTLP export requires `SLOGR_API_KEY` (either `sk_free_*` or `sk_live_*`). Without it, OTLP export is disabled and the agent runs in ANONYMOUS mode (stdout only).

## Implementation

```kotlin
// In OtlpExporter.kt, before any export:
fun export(metrics: List<Metric>) {
    val state = agentConfig.agentState  // ANONYMOUS, REGISTERED, or CONNECTED
    if (state == AgentState.ANONYMOUS) {
        if (!nudgeLogged) {
            logger.info("OTLP export requires a Slogr API key. Get one free at https://slogr.io/keys")
            nudgeLogged = true  // log once, not every cycle
        }
        return
    }
    // ... proceed with export
}
```

## OTLP Headers

When OTLP export is active, include the key in export headers for attribution:

```
Authorization: Bearer sk_free_abc123
X-Slogr-Agent-Id: 550e8400-...  (if CONNECTED, else omitted)
```

This lets a future slogr.io free OTLP endpoint attribute data to the correct tenant without requiring full registration.

## OTLP Resource Attributes

All exported metrics must include these resource attributes:

```
slogr.tenant_id:          <tenant_id from key validation cache or registration response>
slogr.agent_id:           <agent_id if CONNECTED, else "unregistered">
slogr.agent_state:        <"registered" or "connected">
slogr.agent_version:      <agent version string>
service.name:             "slogr-agent"
service.version:          <agent version string>
host.name:                <hostname>
cloud.provider:           <aws/gcp/azure/other>
cloud.region:             <region string>
```

The `slogr.tenant_id` comes from:
- CONNECTED agents: the `tenant_id` returned by `POST /v1/agents` registration
- REGISTERED agents: the `tenant_id` returned by `GET /v1/keys/validate` (cached in `/var/lib/slogr/key_validation.json`)
- If no `tenant_id` available (air-gapped, validation skipped): omit the attribute

## Measurement Method Attribute

Each OTLP metric must include `measurement_method` as a metric attribute:

```
measurement_method: "twamp"   → for TWAMP session data
measurement_method: "icmp"    → for ICMP ping + TCP connect fallback data
```

This is the same discriminator used in the RabbitMQ routing key (`agent.{id}.twamp` vs `agent.{id}.probe`) and the ClickHouse table split (`twamp_raw` vs `probe_raw`).

## What Gets Exported via OTLP

Both TWAMP and probe (ICMP/TCP fallback) measurements are exported via OTLP. The `measurement_method` attribute distinguishes them. The OTLP consumer (Grafana, Prometheus, ClickHouse via OTel Collector, or future slogr.io free endpoint) can filter or route based on this attribute.

## Files

| File | Action |
|------|--------|
| `platform/otlp/OtlpExporter.kt` | MODIFY — add state check, nudge logging, resource attributes with tenant_id |
| `platform/otlp/MetricMapper.kt` | MODIFY — add `measurement_method` attribute to all metrics |
