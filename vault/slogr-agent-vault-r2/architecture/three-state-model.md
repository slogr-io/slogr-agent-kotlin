# Agent Three-State Model

**Status:** Locked
**Replaces:** R1 two-state model (disconnected/connected)

---

## States

```
┌─────────────────────────────────────────────────────┐
│  ANONYMOUS                                           │
│  No key. No backend contact.                         │
│  check + daemon (stdout only)                        │
│  Footer: "→ slogr.io" or "→ slogr.io/enterprise"   │
└────────────────────┬────────────────────────────────┘
                     │ user sets SLOGR_API_KEY=sk_free_*
                     ▼
┌─────────────────────────────────────────────────────┐
│  REGISTERED                                          │
│  OTLP export enabled.                                │
│  Free key from slogr.io/keys (LinkedIn OAuth)        │
│  daemon + check + OTLP to any endpoint               │
│  No RabbitMQ. No Pub/Sub. No SaaS features.          │
└────────────────────┬────────────────────────────────┘
                     │ user sets SLOGR_API_KEY=sk_live_*
                     │ or runs slogr-agent connect --api-key sk_live_*
                     ▼
┌─────────────────────────────────────────────────────┐
│  CONNECTED                                           │
│  OTLP + RabbitMQ + Pub/Sub + full SaaS               │
│  Paid key from SaaS dashboard                        │
│  detection, alerting, investigation, history          │
└─────────────────────────────────────────────────────┘
```

## State Determination

Evaluated once on daemon startup. Key prefix is the gate:

```kotlin
enum class AgentState { ANONYMOUS, REGISTERED, CONNECTED }

fun determineState(apiKey: String?): AgentState = when {
    apiKey == null                       -> ANONYMOUS
    apiKey.startsWith("sk_free_")        -> REGISTERED
    apiKey.startsWith("sk_live_")        -> CONNECTED
    else                                 -> ANONYMOUS  // invalid key format
}
```

## State Capabilities

| Capability | ANONYMOUS | REGISTERED | CONNECTED |
|-----------|-----------|------------|-----------|
| `check` one-shot (stdout) | ✅ | ✅ | ✅ |
| `check` one-shot (JSON) | ✅ | ✅ | ✅ |
| `daemon` continuous (stdout) | ✅ | ✅ | ✅ |
| OTLP export | ❌ | ✅ | ✅ |
| RabbitMQ publish | ❌ | ❌ | ✅ |
| Pub/Sub command subscription | ❌ | ❌ | ✅ |
| Health signal reporting | ❌ | ❌ | ✅ |
| WAL buffering | ❌ | ❌ | ✅ |
| SaaS registration | ❌ | ❌ | ✅ |
| CLI footer nudge | ✅ (slogr.io) | ❌ (already registered) | ❌ (already connected) |

## Startup Logging (Mandatory)

The first log line after startup MUST state the mode and why:

```
# Anonymous:
Starting daemon in ANONYMOUS mode (stdout only)
→ For OTLP export, set SLOGR_API_KEY. Get a free key at https://slogr.io/keys

# Registered:
Starting daemon in REGISTERED mode (OTLP + stdout)

# Connected — explicit:
Connected as acme-aws-us-east1-a1b2c3d (agent_id: 550e8400-...)
Starting daemon in CONNECTED mode (RabbitMQ + OTLP + stdout)

# Connected — auto (mass deployment):
Auto-registering with api.slogr.io...
Connected as acme-aws-us-east1-a1b2c3d (agent_id: 550e8400-...)
Starting daemon in CONNECTED mode (RabbitMQ + OTLP + stdout)
```

## Zero-Reinstall Upgrade

Transitions happen by changing environment variables or running `connect`/`disconnect`. No reinstall. No restart required if the agent supports hot key reload (SIGHUP or config file watch).

```bash
# ANONYMOUS → REGISTERED (add env var, restart daemon or send SIGHUP)
export SLOGR_API_KEY=sk_free_abc123

# REGISTERED → CONNECTED (run connect command)
slogr-agent connect --api-key sk_live_xyz789

# CONNECTED → ANONYMOUS (disconnect)
slogr-agent disconnect
# Deletes credential, closes RabbitMQ/Pub/Sub. Daemon continues in ANONYMOUS mode.
```

## Environment Variable

One env var: `SLOGR_API_KEY`. Accepts both `sk_free_*` and `sk_live_*`. `sk_live_*` is a superset of `sk_free_*`. No `SLOGR_KEY` — that name is retired.
