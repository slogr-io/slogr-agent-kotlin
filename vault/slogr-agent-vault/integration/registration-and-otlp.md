---
status: locked
version: 1.0
depends-on:
  - architecture/data-model
  - security/credential-management
---

# Registration

## Two Registration Paths

### 1. Bootstrap Token (Marketplace / Mesh)

Automated. The token is injected as an environment variable at deploy time.

```
SLOGR_BOOTSTRAP_TOKEN=eyJhbGci...
```

Flow:
1. Agent reads `SLOGR_BOOTSTRAP_TOKEN` from environment
2. Agent reads cloud metadata (IMDS probes — Azure, AWS, GCP, with 2-second timeouts, in parallel)
3. `POST https://api.slogr.io/api/v1/agents/register` with Bearer bootstrap token
4. Request body: `{ cloud, region, instance_id, version }`
5. Response: `{ agent_id, credential (JWT), display_name, pubsub_subscription, rabbitmq: { host, port } }`
6. Agent stores credential to encrypted local file
7. Agent downloads MaxMind ASN database if not present
8. Agent connects to Pub/Sub subscription and RabbitMQ
9. Agent publishes reconnect announcement
10. Bootstrap token env var is consumed — agent never uses it again

### 2. Interactive Connect (Pro $10/month)

User-driven. The user runs `slogr-agent connect` and enters an API key.

```bash
$ slogr-agent connect
Enter your Slogr API key: sk_live_abc123...
Registering agent...
Connected as acme-aws-us-east-1-a1b2c3d
Agent ID: 550e8400-e29b-41d4-a716-446655440000
```

Flow:
1. User runs `slogr-agent connect`
2. Prompts for API key (or reads from `--api-key` flag / `SLOGR_API_KEY` env var)
3. Agent reads cloud metadata (same IMDS probes as bootstrap)
4. `POST https://api.slogr.io/api/v1/agents/connect` with Bearer API key
5. Same response format as bootstrap registration
6. Agent stores credential to encrypted local file
7. Agent downloads MaxMind ASN database if not present
8. Prints confirmation with display name and agent ID

After either path, the agent is in connected state. `slogr-agent status` shows "Connected".

## Disconnect

```bash
$ slogr-agent disconnect
Disconnecting agent acme-aws-us-east-1-a1b2c3d...
Flushing local buffer (23 pending entries)...
Disconnected. Agent will continue running in free mode.
```

Flow:
1. Flush WAL to RabbitMQ (best effort, 10-second timeout)
2. Delete stored credential
3. Close RabbitMQ connection
4. Close Pub/Sub subscription
5. Agent continues running in disconnected mode (if daemon) or exits (if invoked standalone)

Disconnect does NOT call deregister on the SaaS. The agent record stays in Cloud SQL with status "inactive". The user can `slogr-agent connect` again to re-register.

## Subsequent Boots (Connected Mode)

1. Load stored credential from disk
2. Verify JWT is not expired
3. If expired: attempt token refresh via `GET /api/v1/agents/refresh-token`. If refresh fails: fall back to disconnected mode with warning.
4. Refresh RabbitMQ JWT (short-lived, hourly)
5. Connect to Pub/Sub and RabbitMQ
6. Publish reconnect announcement
7. Start scheduler with persisted schedule

---
---

# OTLP/HTTP Exporter

## Overview

Exports metrics via OpenTelemetry Protocol over HTTP. Active when `SLOGR_ENDPOINT` is set (any mode) or always in connected mode (dual export: RabbitMQ + OTLP).

## SLOGR_ENDPOINT Behavior Matrix

This is the complete truth table for how the agent delivers results based on its state and configuration:

| Agent State | SLOGR_ENDPOINT | Outputs | Use Case |
|-------------|----------------|---------|----------|
| Disconnected | Not set | stdout only | Free CLI, local testing |
| Disconnected | Set (e.g. `http://localhost:4318`) | stdout + OTLP/HTTP to endpoint | Free agent → local Grafana/Prometheus |
| Disconnected | Set (e.g. `http://proxy.internal:4318`) | stdout + OTLP/HTTP to proxy | Enterprise: agents → Slogr Proxy |
| Connected | Not set | stdout + RabbitMQ to SaaS | Pro agent, direct SaaS connection |
| Connected | Set | stdout + RabbitMQ to SaaS + OTLP/HTTP to endpoint | Pro agent with local monitoring too |

Key rules:
- `SLOGR_ENDPOINT` is purely additive — it never replaces RabbitMQ in connected mode
- In connected mode, RabbitMQ is the primary data delivery. OTLP is a secondary/parallel export
- `slogr-agent connect` transitions from disconnected → connected. Does NOT change OTLP behavior
- `slogr-agent disconnect` transitions from connected → disconnected. OTLP continues if endpoint is set

## Configuration

| Env Var | Default | Description |
|---------|---------|-------------|
| `SLOGR_ENDPOINT` | (unset) | OTLP/HTTP endpoint URL. If set, agent exports OTLP here. |
| `SLOGR_OTLP_HEADERS` | (unset) | Optional additional HTTP headers (e.g., `Authorization=Bearer token`) |
| `SLOGR_OTLP_TIMEOUT_MS` | 5000 | Export timeout |

## Endpoint

```
POST {SLOGR_ENDPOINT}/v1/metrics
Content-Type: application/x-protobuf
```

Standard OTLP/HTTP endpoint. Compatible with any OTel collector, Prometheus remote-write adapter, Grafana Agent, or the Slogr Proxy.

## Metric Names and Attributes

Defined in `architecture/data-model.md`. Locked — do not change after R1 release.

## Batching

| Parameter | Value |
|-----------|-------|
| Max batch size | 100 metric data points |
| Max batch delay | 10 seconds |
| Whichever comes first triggers export |

## Library

Use `io.opentelemetry:opentelemetry-exporter-otlp` (official OTel Java SDK). Configure with HTTP transport (not gRPC).

## Graceful Degradation

If `SLOGR_ENDPOINT` is set but unreachable:
- Log warning every 60 seconds (not every failed export — avoid log spam)
- Buffer up to 1000 data points in memory
- Retry on next batch interval
- Do not block the measurement pipeline — measurements continue regardless

## CLI Check Mode

In `slogr-agent check` mode, if `SLOGR_ENDPOINT` is set, the single measurement result is exported immediately (no batching) and the process exits after export succeeds or times out.
