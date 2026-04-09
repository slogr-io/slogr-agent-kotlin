---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
---

# CLI Interface

## Binary Name

`slogr-agent`

## Commands

```
slogr-agent <command> [flags]

Commands:
  check        Run a one-shot measurement and exit
  daemon       Run as a long-lived measurement service
  connect      Link this agent to Slogr SaaS (Pro $10/month)
  disconnect   Unlink from Slogr SaaS, return to free mode
  status       Show agent state, connection, version
  version      Print version and build info
  setup-asn    Download MaxMind ASN database for traceroute enrichment
```

## slogr-agent check

One-shot measurement. Results to stdout. Exits after completion.

```
slogr-agent check <target> [flags]

Arguments:
  <target>                  IP address or hostname of TWAMP reflector

Flags:
  --port <int>              Target TWAMP reflector port (default: 862)
  --profile <name>          SLA profile name (default: internet)
  --traceroute              Include traceroute after TWAMP test
  --trace-mode <mode>       Force traceroute mode: icmp, tcp, udp (default: auto — tries ICMP, then TCP/443, then UDP)
  --max-hops <int>          Traceroute max TTL (default: 30)
  --probes <int>            Probes per hop (default: 2)
  --trace-timeout <ms>      Per-hop timeout in ms (default: 2000)
  --packets <int>           Override profile packet count
  --interval <ms>           Override profile packet interval (ms)
  --dscp <int>              Override profile DSCP value (0-63)
  --packet-size <bytes>     Override profile packet size
  --timing <mode>           Timing mode: fixed, poisson (default: fixed)
  --poisson-lambda <float>  Poisson lambda (requires --timing poisson)
  --poisson-max <ms>        Poisson max interval (requires --timing poisson)
  --auth-mode <mode>        TWAMP auth: unauthenticated, authenticated (default: unauthenticated)
  --key-id <id>             Key ID for authenticated mode
  --key-secret <secret>     Shared secret for authenticated mode
  --format <fmt>            Output format: text, json (default: text)
  --quiet                   Suppress banner, show result only
```

### Output: text format (default)

```
TWAMP test to 10.0.1.5:862 (profile: voip)
  RTT     avg=18.2ms  min=14.1ms  max=42.3ms
  Jitter  2.4ms
  Loss    0.0% (100/100 packets)
  Grade   GREEN

Traceroute to 10.0.1.5 (ICMP, 12 hops):
   1  192.168.1.1      0.4ms   AS—
   2  10.0.0.1         1.2ms   AS—
   3  72.14.215.85     3.8ms   AS15169 (GOOGLE)
   4  108.170.252.1    4.1ms   AS15169 (GOOGLE)
   ...
  12  10.0.1.5         18.1ms  AS16509 (AMAZON)

ASN path: AS15169 → AS16509
Path changed: NO
```

### Output: json format

```json
{
  "twamp": {
    "target": "10.0.1.5",
    "port": 862,
    "profile": "voip",
    "rtt_avg_ms": 32.0,
    "rtt_min_ms": 28.2,
    "rtt_max_ms": 56.1,
    "fwd_avg_rtt_ms": 18.2,
    "fwd_min_rtt_ms": 14.1,
    "fwd_max_rtt_ms": 42.3,
    "fwd_jitter_ms": 2.4,
    "fwd_loss_pct": 0.0,
    "packets_sent": 100,
    "packets_recv": 100,
    "grade": "GREEN"
  },
  "traceroute": {
    "mode": "icmp",
    "hops": [
      {"ttl": 1, "ip": "192.168.1.1", "rtt_ms": 0.4, "asn": null},
      {"ttl": 3, "ip": "72.14.215.85", "rtt_ms": 3.8, "asn": 15169, "asn_name": "GOOGLE"}
    ]
  },
  "asn_path": [15169, 16509],
  "path_changed": false
}
```

## slogr-agent daemon

Long-lived service. Runs scheduled tests continuously.

```
slogr-agent daemon [flags]

Flags:
  --config <path>           Schedule config file (YAML/JSON)
  --bootstrap-token <token> Bootstrap token for auto-registration (or SLOGR_BOOTSTRAP_TOKEN env)
  --data-dir <path>         Data directory for WAL, credentials, ASN DB (default: /opt/slogr/data)
  --log-level <level>       Log level: debug, info, warn, error (default: info)
  --log-format <fmt>        Log format: text, json (default: json)
  --max-concurrent <int>    Max concurrent TWAMP sessions (default: 20)
  --responder-port <int>    TWAMP responder listen port (default: 862)
  --responder-whitelist <ips> Comma-separated allowed controller IPs (default: deny-all except mesh)
```

### Daemon Config File Format (--config)

The config file defines the test schedule. Each target can independently enable or disable traceroute.

```yaml
# schedule.yaml
interval_seconds: 300           # global default, per-target overridable

targets:
  - path_id: "550e8400-e29b-41d4-a716-446655440001"
    target: 10.0.1.5
    port: 862
    profile: voip
    traceroute: true              # default: true
    interval_seconds: 300         # optional override

  - path_id: "550e8400-e29b-41d4-a716-446655440002"
    target: 10.0.2.10
    port: 862
    profile: gaming
    traceroute: false             # TWAMP only, no traceroute for this target

  - path_id: "550e8400-e29b-41d4-a716-446655440003"
    target: 192.168.1.1           # Cisco router
    port: 862
    profile: internet
    traceroute: true
    auth_mode: authenticated      # router requires authenticated TWAMP
    key_id: "router-key-1"
    key_secret_env: "SLOGR_KEY_ROUTER1"  # read secret from env var, never in config file
```

When the agent is in connected mode and receives a `set_schedule` command via Pub/Sub, it replaces the local config file with the command's schedule. The `set_schedule` payload also supports per-target `traceroute: true/false`.

## slogr-agent connect

Interactive SaaS registration.

```
slogr-agent connect [flags]

Flags:
  --api-key <key>           API key (or prompts interactively)
  --data-dir <path>         Where to store credentials (default: /opt/slogr/data)
```

## slogr-agent disconnect

```
slogr-agent disconnect [flags]

Flags:
  --data-dir <path>         Where credentials are stored (default: /opt/slogr/data)
  --force                   Skip buffer flush, disconnect immediately
```

## slogr-agent status

```
$ slogr-agent status
Slogr Agent v1.0.0
State:           Connected (Pro)
Agent ID:        550e8400-e29b-41d4-a716-446655440000
Display Name:    acme-aws-us-east-1-a1b2c3d
Tenant:          acme-corp
RabbitMQ:        connected (mq.slogr.io:5671)
Pub/Sub:         polling (5s interval)
OTLP Endpoint:   https://proxy.internal:4318
Schedule:        42 paths, 300s interval
Buffer:          3 pending entries
ASN Database:    loaded (2026-03-15, 7.2 MB)
Uptime:          4d 12h 33m
```

## slogr-agent setup-asn

```
slogr-agent setup-asn [flags]

Flags:
  --data-dir <path>         Where to save the database (default: /opt/slogr/data)
  --license-key <key>       MaxMind license key (or MAXMIND_LICENSE_KEY env var)
  --url <url>               Direct download URL (for air-gapped / Slogr-hosted mirror)
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Invalid arguments |
| 3 | Target unreachable (check mode) |
| 4 | Authentication failure (connect, registration) |
| 5 | TWAMP session failed (check mode) |

## Environment Variables (all commands)

| Variable | Purpose |
|----------|---------|
| `SLOGR_BOOTSTRAP_TOKEN` | Bootstrap token for daemon auto-registration |
| `SLOGR_API_KEY` | API key for `connect` (alternative to interactive prompt) |
| `SLOGR_ENDPOINT` | OTLP/HTTP export endpoint |
| `SLOGR_OTLP_HEADERS` | Additional OTLP headers |
| `SLOGR_DATA_DIR` | Data directory override (default: /opt/slogr/data) |
| `SLOGR_NATIVE_DIR` | JNI library directory override (default: /opt/slogr/lib) |
| `SLOGR_LOG_LEVEL` | Log level override |
| `MAXMIND_LICENSE_KEY` | MaxMind license key for ASN database download |
