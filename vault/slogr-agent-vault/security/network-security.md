---
status: locked
version: 1.0
depends-on:
  - security/threat-model
---

# Network Security

## TLS Requirements

| Connection | TLS Required | Certificate Validation |
|------------|-------------|----------------------|
| Registration API (`api.slogr.io`) | HTTPS mandatory | System trust store. No `verify=false`. |
| RabbitMQ | TLS mandatory (port 5671) | System trust store. |
| Pub/Sub | HTTPS (GCP client library handles this) | Google-managed. |
| OTLP export | HTTPS recommended, HTTP allowed with warning | If HTTP: log warning on stderr at startup. |
| TWAMP control (TCP) | No TLS (RFC 5357 uses authenticated mode instead) | Authenticated mode provides HMAC integrity. |
| RabbitMQ token refresh | HTTPS mandatory | System trust store. |

Never implement a `--skip-tls-verify` flag. There is no legitimate use case.

## Egress Restrictions

The agent makes outbound connections only. It never listens on a public port except for the TWAMP responder (which is rate-limited and whitelisted).

| Destination | Port | Protocol | Purpose |
|-------------|------|----------|---------|
| `api.slogr.io` | 443 | HTTPS | Registration, token refresh |
| RabbitMQ broker (from registration response) | 5671 | AMQPS | Measurement publishing |
| GCP Pub/Sub API | 443 | HTTPS | Command polling |
| `SLOGR_ENDPOINT` (if set) | 4318 (default) | HTTP(S) | OTLP export |
| `download.maxmind.com` | 443 | HTTPS | ASN database download (setup-asn only) |
| `releases.slogr.io` | 443 | HTTPS | Binary upgrades |
| TWAMP targets | configurable | TCP+UDP | Measurement sessions |

No other outbound connections should be made. Firewall documentation for customers should list exactly these destinations.

---

# Anti-Abuse

## TWAMP Responder Hardening

The responder is the primary attack surface — it accepts inbound connections.

| Control | Value | Configurable |
|---------|-------|-------------|
| IP whitelist | Deny-by-default for customer deployments. Mesh agents: allow mesh IPs only. | Yes — `slogr.responder.whitelist` |
| Max concurrent control connections | 20 | Yes |
| Max concurrent reflector sessions | 100 | Yes |
| New sessions per minute per source IP | 50 | Yes |
| Max packets/second (all sessions) | 10,000 | Yes |
| Idle control session timeout | 60 seconds | Yes |
| Test session max duration | 300 seconds (auto-expire) | Yes |

## Resource Caps

| Resource | Limit | Action on Exceed |
|----------|-------|-----------------|
| Heap memory | 384 MB (`-Xmx384m`) | JVM OOM → systemd restarts |
| WAL disk usage | 100 MB | Oldest entries evicted |
| ASN cache entries | 10,000 (LRU) | Evict oldest |
| MaxMind DB memory-map | ~7 MB | Fixed by file size |
| Concurrent TWAMP sender sessions | 20 | Semaphore — new sessions wait |
| Log file size | 50 MB, 3 rotations | Logback rolling policy |
| Stdout output rate (CLI mode) | No limit (one-shot) | N/A |

## Rate Limiting on Outbound

| Destination | Rate Limit |
|-------------|-----------|
| RabbitMQ publish | No artificial limit — bounded by session interval (5 min) |
| Pub/Sub poll | Every 5 seconds |
| OTLP batch | 100 metrics or 10 seconds |
| WAL replay (reconnect) | 10 entries/second |
| Token refresh | Once per hour, retry every 30s on failure |
| Registration API | Once per agent lifetime |
