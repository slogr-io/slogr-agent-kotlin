---
status: locked
version: 1.0
depends-on: []
claude-code-context:
  - "Read at the start of every session"
  - "This is the mental model for what you are building"
---

# System Overview

## What the Agent Is

The Slogr Agent is a lightweight network measurement probe written in Kotlin/JVM. It measures network path quality using TWAMP (RFC 5357), traceroute, and ASN path analysis. It is Layer 1 in the Slogr four-layer architecture. It produces data — it never processes, stores long-term, or displays data.

## One Binary, Two States

The agent ships as a single binary (`slogr-agent`) with two operational states:

### Disconnected (free)
No signup, no backend, no billing. The agent runs locally and produces output locally.

```bash
slogr-agent check api.stripe.com --profile voip           # one-shot test → stdout
slogr-agent check api.stripe.com --traceroute --format json # with traceroute, JSON output
slogr-agent daemon --config schedule.yaml                   # continuous scheduled tests
```

If `SLOGR_ENDPOINT` is set, the agent also exports metrics via OTLP/HTTP to that URL (local Grafana, Prometheus, or the Slogr Enterprise proxy). The agent never contacts the Slogr SaaS in this state.

### Connected (Pro, $10/month per agent)
The user runs `slogr-agent connect` once, enters an API key, and the agent stores SaaS credentials locally. After that, all commands automatically send data to the SaaS in addition to any local output.

```bash
slogr-agent connect                    # interactive: enter API key → stores credentials
slogr-agent check api.stripe.com       # results → stdout + OTLP + RabbitMQ to SaaS
slogr-agent daemon                     # continuous → stdout + OTLP + RabbitMQ + Pub/Sub commands
slogr-agent disconnect                 # removes credentials, back to free mode
slogr-agent status                     # shows: connected/disconnected, endpoint, version
```

Connected agents register with the SaaS, appear in the agent directory, receive commands via Pub/Sub, and publish measurements to RabbitMQ.

### Marketplace / Mesh Agents
Pre-connected via bootstrap token at deploy time (automated equivalent of `slogr-agent connect`). Same binary, same code. Mesh agents run under `tenant_id = 00001` (Slogr-managed). All mesh-vs-customer logic lives in the SaaS BFF, not in the agent.

### Enterprise Path (via Slogr Proxy, separate binary)
Enterprise customers deploy the Slogr Proxy in their network. Their agents stay disconnected and set `SLOGR_ENDPOINT` to point at the proxy. The proxy handles registration, enrichment, and upstream forwarding. The agent never knows the SaaS exists.

## What the Agent Does

Every measurement cycle (5 minutes default, configurable):

1. **Measure** — Run TWAMP session against the destination. The destination can be another Slogr agent OR any RFC 5357 compliant TWAMP reflector (Cisco, Juniper, or any standards-compliant router/switch). Collect per-packet timing: forward/reverse RTT (min/avg/max), jitter, packet loss, TTL, out-of-order detection, reflector processing time. Supports Poisson and fixed-interval timing. Supports unauthenticated and authenticated modes.
   - **Agent as controller:** Initiates TWAMP sessions to any compliant reflector.
   - **Agent as reflector:** Accepts TWAMP sessions from any compliant controller (including routers).
2. **Trace** — Run traceroute to the destination via native JNI probes. Multi-mode: ICMP, then UDP. Keep the mode with the most resolved hops.
3. **Resolve** — Translate traceroute hop IPs to ASN numbers via Team Cymru bulk whois. LRU cache with 24-hour TTL, bounded to 10,000 entries.
4. **Detect** — Compare current ASN path to previous. Flag path changes with changed hops and primary ASN change.
5. **Evaluate** — Score results against SLA profile thresholds (green/yellow/red).
6. **Deduplicate** — SHA256 of canonical result. Skip if identical to previous report for this session.
7. **Buffer** — Write results to local write-ahead log (WAL).
8. **Publish** — If connected: send to RabbitMQ. Mark as published only after broker ACK. If disconnected with `SLOGR_ENDPOINT`: export via OTLP/HTTP.
9. **Report health** — If connected: send periodic health signal (buffer state, failure counters, timestamps).

## Two Data Planes

| Plane | Transport | Direction | When |
|-------|-----------|-----------|------|
| Data | RabbitMQ (AWS) | Agent → Backend | Connected mode only |
| Control | GCP Pub/Sub | Backend → Agent | Connected mode only |
| OTLP | HTTP to SLOGR_ENDPOINT | Agent → Proxy/Collector | When SLOGR_ENDPOINT is set |

Data and control planes are independent. A control plane outage does not affect data delivery.

## Agent Lifecycle

```
1. INSTALL      — yum/apt/brew install, Docker pull, or marketplace AMI launch
2. [CONNECT]    — Optional: slogr-agent connect (Pro) or bootstrap token (marketplace)
3. CONFIGURE    — CLI flags, config file, or set_schedule command via Pub/Sub
4. MEASURE      — Runs test schedule continuously (daemon) or once (check)
5. REPORT       — Publishes to RabbitMQ (connected), OTLP (if endpoint set), stdout
6. [UPDATE]     — Receives upgrade command → downloads binary, verifies checksum, swaps, restarts
7. [DISCONNECT] — slogr-agent disconnect or deregister command
```

## Scale Context

| Metric | Value |
|--------|-------|
| Target agent count | 100,000+ (connected mode, global mesh) |
| Sessions per agent | ~300 (paths in schedule) |
| Sessions per 5-min window (global) | ~100,000 |
| TWAMP rows per day | ~28,800,000 |
| Traceroute updates per window | ~1,000–5,000 (change-only) |
| Target instance | t3.micro (2 vCPU, 1 GB RAM) |
| Agent memory ceiling | 512 MB |
| Standalone binary size target | < 50 MB (JAR with JNI libs) |

## Language and Heritage

Written in **Kotlin** targeting JVM. The Kotlin agent is a clean-room implementation based on RFC 5357 and the Slogr specification vault. The Java agent's architecture was studied for protocol understanding. The traceroute, ASN resolution, and path change detection logic follows patterns from the Python agent's enrichment layer. The Kotlin agent replaces both prior implementations.

Future: Kotlin Multiplatform will compile the shared core (session model, SLA evaluation, path comparison, report schema) to native (iOS, standalone binary) and JS (Chrome extension). The JVM target is the first and primary build.
