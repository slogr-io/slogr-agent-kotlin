---
status: locked
version: 1.0
depends-on:
  - architecture/data-model
  - security/credential-management
claude-code-context:
  - "Read when implementing RabbitMQ publishing"
  - "Cross-reference with SaaS vault: layer2/rabbitmq-topology.md"
---

# RabbitMQ Publisher

## Overview

Publishes measurement results, traceroute snapshots, and health signals to RabbitMQ. Active only in connected mode. Uses JWT auth plugin — no per-agent users on the broker.

## Connection

| Property | Value |
|----------|-------|
| Host | From `AgentCredential.rabbitmqHost` (returned at registration) |
| Port | 5671 (AMQPS — TLS mandatory) |
| Auth | JWT token via SASL EXTERNAL or custom mechanism (RabbitMQ JWT auth plugin) |
| Connection name | `slogr-agent-{agent_id}` (for broker-side debugging) |
| Heartbeat | 30 seconds |
| Channel count | 1 (single channel, sufficient for agent throughput) |

## Exchange and Routing Keys

```
Exchange: slogr.measurements (topic, durable)

Routing keys:
  agent.{agent_id}.twamp        ← MeasurementResult
  agent.{agent_id}.traceroute   ← TracerouteResult
  agent.{agent_id}.health       ← HealthSnapshot
```

The JWT scope restricts this agent to `agent.{its_own_agent_id}.*` only. Publishing to any other routing key is rejected by the broker.

## Message Format

JSON serialization of the data classes. Content type: `application/json`. Content encoding: `utf-8`.

```kotlin
val props = AMQP.BasicProperties.Builder()
    .contentType("application/json")
    .contentEncoding("utf-8")
    .deliveryMode(2)                        // persistent
    .timestamp(Date())
    .headers(mapOf(
        "schema_version" to 1,
        "agent_id" to agentId.toString(),
        "tenant_id" to tenantId.toString()
    ))
    .build()
```

**Important:** Include `tenant_id` in the message body as well as the header. The Ingest Bridge stamps/validates `tenant_id` on each message, but the agent must provide it. The `tenant_id` comes from `AgentCredential.tenantId` (set at registration).

## Publisher Confirms

Enable publisher confirms (`channel.confirmSelect()`). Wait for broker ACK before marking the WAL entry as published. Timeout: 5 seconds per publish. On NACK or timeout: leave WAL entry as pending, retry on next cycle.

## Reconnection

| Event | Action |
|-------|--------|
| Connection lost | Log warning, switch to WAL-only mode, start reconnect loop |
| Reconnect attempt | Exponential backoff: 3s, 6s, 12s, 24s, 48s, 60s max |
| JWT expired | Refresh JWT via `GET /api/v1/agents/rabbitmq-token` before reconnecting |
| Reconnect success | Drain WAL at 10 entries/second |

## Deduplication

Before publishing, compute SHA256 of the canonical JSON (sorted keys) for each `MeasurementResult`. Compare to previous hash for this `sessionId`. If identical, skip publish. This prevents sending unchanged results on stable paths (from Python agent's delta detection logic).

## Library

Use `com.rabbitmq:amqp-client` (official RabbitMQ Java client — works with Kotlin).
