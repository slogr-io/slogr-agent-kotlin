---
status: locked
version: 1.0
depends-on:
  - slogr-agent-vault/architecture/system-overview
  - slogr-agent-vault-r2/architecture/three-state-model
---

# System Overview — Layer 1.1

## Position in the Stack

```
Layer 1 — Agent (server, Docker, K8s)        ← L1 R1/R2 vault
Layer 1.1 — Desktop Agent (Windows, macOS)   ← THIS VAULT
Layer 2 — Data Platform (RabbitMQ → ClickHouse)
Layer 2.5 — Agent Control Plane (Cloud SQL + Pub/Sub)
Layer 3 — SaaS Product (React + BFF on Cloud Run)
```

Layer 1.1 is a branch of Layer 1. It shares the measurement engine but differs in:
- **UI:** Full window app + system tray (vs headless CLI daemon)
- **Targets:** Auto-discovered Slogr reflectors (vs operator-configured targets)
- **Profile selection:** User picks in the UI (vs `set_schedule` command or config file)
- **Local storage:** SQLite history for free users (vs no local history on servers)
- **Packaging:** GUI installer with auto-start (vs headless package managers)
- **Privileges:** Always pure-Java fallback mode (vs JNI with CAP_NET_RAW on servers)

## What the Desktop Agent Does

Every measurement cycle (user-configured interval, default 5 minutes):

1. **Reflector discovery** — On first launch, call `GET /v1/reflectors` and select nearest free-tier endpoints. Cache the list, refresh every 24h.
2. **TWAMP** — Run sessions against selected reflectors using pure-Java `DatagramSocket`. Per-packet stats: RTT (min/avg/max), jitter, loss. No TTL capture, no DSCP (pure-Java limitation).
3. **Traceroute** — `ProcessBuilder` wrapping OS `tracert` (Windows) or `traceroute -U` (macOS, UDP mode). Not production-grade — best-effort for desktop users.
4. **ASN resolution** — Local MaxMind GeoLite2-ASN MMDB.
5. **Path change detection** — ASN path comparison, same as server agent.
6. **SLA evaluation** — Against user-selected profile (Internet, Gaming, Streaming, VoIP).
7. **Local storage** — Write result to SQLite (24h retention, all states).
8. **UI update** — Update tray icon color, refresh main window if open.
9. **Publish (if CONNECTED)** — RabbitMQ + OTLP, same as server agent.
10. **Health signal (if CONNECTED)** — Every 60 seconds, same as server agent.

## Pure-Java Fallback Mode (Always Active on Desktop)

The desktop app always runs in pure-Java fallback mode. No JNI native library is loaded. This means:

| Capability | Server (JNI) | Desktop (Pure-Java) |
|---|---|---|
| TWAMP UDP | Raw socket via `twampUdp.c` | `DatagramSocket` |
| TTL capture | ✅ `IP_RECVTTL` via `recvmsg()` | ❌ Not available |
| DSCP marking | ✅ `setsockopt(IP_TOS)` | ❌ Not available |
| Kernel timestamps | ✅ `SO_TIMESTAMPING` | ❌ `System.nanoTime()` instead |
| Traceroute | JNI ICMP raw sockets | `ProcessBuilder` wrapping OS command |
| Port 862 binding | ✅ `CAP_NET_BIND_SERVICE` | ❌ Uses ephemeral port |
| Admin/root required | Yes (capabilities) | No |

This is acceptable for desktop use. Desktop users care about "is my connection good?" — microsecond-precision timestamps and DSCP are irrelevant for that.

## TWAMP on Desktop — Reflector Compatibility

The desktop agent connects to Slogr mesh reflectors on port 862. The TWAMP control connection (TCP) and test session (UDP) work identically to the server agent. The only difference is the transport implementation underneath:

- Server: JNI `twampUdp.c` → raw UDP socket → kernel timestamps
- Desktop: Kotlin `DatagramSocket` → JVM UDP socket → `System.nanoTime()` timestamps

The reflector doesn't know or care which transport the controller uses. The RFC 5357 wire format is identical.

## Traceroute on Desktop

### Windows
```kotlin
ProcessBuilder("tracert", "-d", "-w", "2000", "-h", "30", target)
```
Parses `tracert` output. ICMP-based. Works without admin privileges.

### macOS
```kotlin
ProcessBuilder("traceroute", "-n", "-U", "-m", "30", "-w", "2", target)
```
UDP mode (`-U`). Does NOT require root. ICMP mode would require root or a privileged helper, which we've decided against (ADR-052).

## Data Flow

```
                    ANONYMOUS / REGISTERED
                    ┌──────────────────────────┐
                    │  Reflector Discovery      │
                    │  → TWAMP test             │
                    │  → Traceroute             │
                    │  → SLA evaluation         │
                    │  → SQLite (24h)           │
                    │  → UI update              │
                    │  → OTLP (if REGISTERED)   │
                    └──────────────────────────┘

                    CONNECTED (adds)
                    ┌──────────────────────────┐
                    │  → RabbitMQ publish       │
                    │  → Pub/Sub commands       │
                    │  → Health signals         │
                    │  → WAL buffering          │
                    │  → SaaS Agent Directory   │
                    └──────────────────────────┘
```

## Registration Fields — Desktop-Specific Values

When a CONNECTED desktop agent calls `POST /v1/agents`:

```json
{
  "machine_fingerprint": "SHA256(mac + hostname + UUID)",
  "cloud": "residential",
  "region": "<IP-geolocated, e.g. 'pk-rawalpindi' or 'us-california'>",
  "instance_id": null,
  "agent_version": "1.1.0",
  "public_ip": "<detected>",
  "private_ip": "<local IP>",
  "hostname": "<machine hostname>",
  "os_name": "windows" | "macos",
  "os_version": "Windows 11 23H2" | "macOS 14.4",
  "os_arch": "amd64" | "aarch64",
  "mac_address": "<primary NIC MAC>",
  "runtime": "jvm",
  "runtime_version": "21.0.1",
  "native_mode": false,
  "cpu_cores": 8,
  "memory_mb": 16384
}
```

The `cloud: "residential"` and `native_mode: false` fields distinguish desktop agents from server agents in the Agent Directory and in ClickHouse queries.

## IP Geolocation for `src_region`

The desktop app resolves its own public IP and geolocates it at registration time:
1. Call a lightweight IP echo service (e.g., `https://api.slogr.io/v1/myip`)
2. Use MaxMind GeoLite2-City on the server side to resolve to a region string
3. Store the region string in the registration response and locally

Alternatively, the `GET /v1/reflectors` response could include the caller's detected region. This avoids an extra API call.
