---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
claude-code-context:
  - "Read before writing any network-facing code"
  - "Every trust boundary listed here must have validation"
---

# Threat Model

## Asset Inventory

| Asset | Impact if Compromised |
|-------|-----------------------|
| Agent JWT credential | Attacker can publish fake measurements, receive commands, impersonate agent |
| RabbitMQ JWT | Attacker can publish to measurement exchange, potentially poison data pipeline |
| Bootstrap token | Attacker can register rogue agents under a customer's tenant |
| Private key on disk | Attacker can decrypt stored credentials |
| TWAMP measurement data | Low — public network path data, not customer application data |
| Agent binary | If tampered, attacker has persistent presence on customer infrastructure |

## Trust Boundaries

### 1. RabbitMQ Messages (Agent → Broker)

The agent publishes to RabbitMQ. The JWT scope restricts which routing keys the agent can use. The Ingest Bridge validates payloads before forwarding to Pub/Sub.

**Threats:** Compromised agent publishes garbage data. Compromised agent floods the exchange.

**Mitigations:** JWT scope limits to `agent.{own_id}.*` only. Ingest Bridge validates schema. RabbitMQ rate limiting per connection. ClickHouse INSERT DEDUPLICATION catches replays.

### 2. Pub/Sub Commands (Backend → Agent)

The agent polls its Pub/Sub subscription. Commands are JSON with a signed envelope.

**Threats:** Attacker injects commands if they gain access to the Pub/Sub topic. Malicious `upgrade` command points to attacker-controlled binary.

**Mitigations:** Per-agent filtered subscription (only receives commands for its own `agent_id`). Upgrade command: agent MUST verify `download_url` is on a Slogr-controlled domain AND verify SHA-256 checksum before applying. Agent rejects any upgrade command where checksum doesn't match.

### 3. TWAMP Sessions (Network)

The agent opens TCP control sessions and exchanges UDP test packets with targets. Targets can be other agents or third-party routers.

**Threats:** Man-in-the-middle on TWAMP control session. Reflector receives probes from unauthorized sources (DDoS amplification). Malicious reflector sends crafted packets to exploit buffer overflow in JNI layer.

**Mitigations:** Authenticated TWAMP mode (HMAC on control messages). IP whitelist on responder (configurable, deny-by-default for non-mesh deployments). JNI code validates packet lengths before processing. Responder rate-limits incoming sessions.

### 4. OTLP/HTTP Export (Agent → Proxy/Collector)

When `SLOGR_ENDPOINT` is set, the agent sends metrics over HTTP.

**Threats:** MITM if endpoint is HTTP not HTTPS. Endpoint receives data from unauthorized agents.

**Mitigations:** Warn on stderr if `SLOGR_ENDPOINT` is HTTP (not HTTPS). The proxy authenticates agents if configured. OTLP data is measurement metrics only — no credentials or PII.

### 5. Local Disk (Credential Store + WAL)

Credentials and buffered measurements are stored on local disk.

**Threats:** Local attacker reads credentials. Local attacker tampers with WAL data.

**Mitigations:** Credential file encrypted at rest (AES-256-GCM, key derived from machine identity). File permissions: 0600, owned by `slogr` user. WAL data is measurement data (low sensitivity) but should still be readable only by the agent user.

### 6. Registration Endpoint (Agent → SaaS API)

One-time HTTPS call to register.

**Threats:** MITM intercepts bootstrap token. DNS spoofing redirects to fake API.

**Mitigations:** TLS required (HTTPS only, no `verify=False`). Bootstrap token is single-use — second use returns 410 Gone. Token lifetime is 24 hours.

### 7. CLI Input (User → Agent)

`slogr-agent check --target <ip>` takes user input.

**Threats:** Argument injection if target IP is passed unsanitized to subprocess or JNI.

**Mitigations:** All inputs validated before use. IP addresses parsed with `InetAddress.getByName()` — rejects non-IP strings. Port numbers validated as 1-65535. Profile names validated against known list. No shell invocation anywhere — all external calls are JNI (structured arguments) or HTTP (library handles encoding).

## Anti-DDoS Measures

The TWAMP responder is a potential DDoS amplification vector — it receives small control packets and responds with larger test packets.

| Control | Implementation |
|---------|---------------|
| IP whitelist | Configurable list of allowed controller IPs. Deny-by-default for customer deployments. Mesh agents allow other mesh agents only. |
| Session rate limit | Max 50 new sessions per minute from any single IP |
| Concurrent session limit | Max 100 active reflector sessions total |
| Packet rate limit | Max 10,000 packets/second across all sessions |
| Control connection limit | Max 20 concurrent TCP control connections |
| Timeout enforcement | Idle control sessions closed after 60 seconds. Test sessions auto-expire after configured duration. |

## Security Invariants

1. No hardcoded credentials anywhere in the codebase
2. No `verify=false` or TLS bypass anywhere
3. No shell invocation (`Runtime.exec`, `ProcessBuilder` with shell) — all external calls via JNI or HTTP client libraries
4. No `eval()`, `ScriptEngine`, or dynamic code execution
5. All network inputs validated before processing
6. All file paths validated to prevent directory traversal
7. Credential file encrypted at rest, permissions 0600
8. Upgrade binaries verified by SHA-256 checksum before execution
