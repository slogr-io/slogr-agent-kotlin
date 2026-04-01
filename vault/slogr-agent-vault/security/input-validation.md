---
status: locked
version: 1.0
depends-on:
  - security/threat-model
---

# Input Validation

## Every Untrusted Input Boundary

### 1. CLI Arguments
| Input | Validation |
|-------|-----------|
| `--target` | `InetAddress.getByName()` — must resolve to valid IPv4/IPv6. Reject hostnames in daemon mode (DNS can change). CLI check mode allows hostnames for convenience. |
| `--port` | Integer 1-65535 |
| `--profile` | Must match a known profile name from the profile registry |
| `--max-hops` | Integer 1-255 |
| `--probes` | Integer 1-10 |
| `--trace-timeout` | Integer 100-30000 (ms) |
| `--format` | Enum: `text`, `json`, `otlp` |
| `--config` | File path — must exist, must be readable, must parse as valid YAML/JSON |

### 2. Pub/Sub Command Payloads
All fields validated before dispatching to command handlers:
| Field | Validation |
|-------|-----------|
| `command_id` | Valid UUID |
| `command_type` | Enum: `run_test`, `set_schedule`, `push_config`, `upgrade`, `deregister` |
| `agent_id` | Must match this agent's ID |
| `tenant_id` | Must match this agent's tenant ID |
| `payload.target_ip` | Valid IP address via `InetAddress` |
| `payload.download_url` | Must be HTTPS, must be on `releases.slogr.io` domain |
| `payload.checksum` | Must match `sha256:[64 hex chars]` pattern |
| `payload.interval_seconds` | Integer 60-86400 |
| `payload.targets[].path_id` | Valid UUID |

Reject entire command if any field fails validation. Respond with `status: failed` and `error` describing the validation failure.

### 3. TWAMP Control Session (TCP)
| Input | Validation |
|-------|-----------|
| Source IP | Checked against IP whitelist (if enabled) |
| ServerGreeting fields | Packet length validated before parsing |
| SetUpResponse mode | Must be a supported mode (unauthenticated or authenticated) |
| RequestTWSession params | Port range validated, DSCP 0-63, packet count > 0 |
| All packet lengths | Validated against expected sizes before any field access |

### 4. TWAMP Test Packets (UDP)
| Input | Validation |
|-------|-----------|
| Packet length | Must match expected TWAMP test packet size (± padding) |
| Sequence number | Validated for monotonicity (out-of-order detection, not rejection) |
| Timestamps | Validated as reasonable (not year 2000 or year 3000) |

### 5. Registration API Response
| Field | Validation |
|-------|-----------|
| `agent_id` | Valid UUID |
| `credential` | Valid JWT (decode header, verify not expired) |
| `rabbitmq.host` | Valid hostname |
| `rabbitmq.port` | Integer 1-65535 |
| `pubsub_subscription` | Non-empty string matching expected pattern |

### 6. MaxMind MMDB File
| Check | Action |
|-------|--------|
| File exists | Gracefully degrade — traceroute works without ASN |
| File is valid MMDB | Validate header on load. Reject corrupted files. |
| File is not stale | Warn if file is older than 90 days |

## Validation Utility

```kotlin
object Validate {
    fun ip(input: String): InetAddress           // throws on invalid
    fun port(input: Int): Int                     // throws if not 1-65535
    fun uuid(input: String): UUID                 // throws on invalid
    fun dscp(input: Int): Int                     // throws if not 0-63
    fun inRange(value: Int, min: Int, max: Int, name: String): Int
    fun slogrDomain(url: String): URL             // throws if not releases.slogr.io
    fun sha256Checksum(input: String): String      // throws if not sha256:[64hex]
}
```

All validation happens at the boundary — by the time data reaches the engine, it is guaranteed valid.
