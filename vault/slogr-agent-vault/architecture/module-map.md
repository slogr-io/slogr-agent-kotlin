---
status: locked
version: 1.0
depends-on:
  - architecture/data-model
claude-code-context:
  - "Read to understand how modules connect"
  - "No module may depend on something not listed here"
---

# Module Map

## Gradle Multi-Module Layout

```
slogr-agent/
├── build.gradle.kts                    ← Root build, version catalog
├── settings.gradle.kts
├── contracts/                          ← Shared data classes + interfaces
│   └── src/main/kotlin/io/slogr/agent/contracts/
├── engine/                             ← Measurement core
│   └── src/main/kotlin/io/slogr/agent/engine/
│       ├── twamp/                      ← TWAMP RFC 5357 implementation
│       ├── traceroute/                 ← Traceroute via JNI
│       ├── asn/                        ← MaxMind MMDB local lookup
│       ├── pathchange/                 ← Path change detection
│       └── sla/                        ← SLA evaluation
├── native/                             ← JNI C library
│   └── src/main/c/
│       ├── twampUdp.c                  ← Raw UDP sockets (from Java agent)
│       ├── twampUdp.h
│       ├── traceroute.c                ← ICMP/UDP probe sockets (new)
│       └── traceroute.h
├── platform/                           ← Platform shell (transport, CLI, scheduling)
│   └── src/main/kotlin/io/slogr/agent/platform/
│       ├── cli/                        ← CLI parser, commands
│       ├── scheduler/                  ← Test scheduling
│       ├── rabbitmq/                   ← RabbitMQ publisher (JWT auth)
│       ├── pubsub/                     ← Pub/Sub subscriber + command handlers
│       ├── registration/               ← Bootstrap token + slogr connect
│       ├── otlp/                       ← OTLP/HTTP exporter
│       ├── buffer/                     ← Local WAL + replay
│       ├── health/                     ← Health signal generation
│       └── credential/                 ← Local credential storage
└── app/                                ← Main entry point, wires everything together
    └── src/main/kotlin/io/slogr/agent/
        └── Main.kt
```

## Module Dependency Graph

```
contracts  ←  (everything depends on this, no outbound deps)
    ↑
engine     ←  depends on: contracts, native (JNI)
    ↑
platform   ←  depends on: contracts, engine (via MeasurementEngine interface)
    ↑
app        ←  depends on: contracts, engine, platform (wires and starts)
```

Strict rule: `engine` never imports from `platform`. `platform` calls `engine` only through the `MeasurementEngine` interface defined in `contracts`.

## Module Specifications

### contracts/ — Shared Data Classes and Interfaces

Contains all data classes from `architecture/data-model.md` and the core interfaces (`MeasurementEngine`, `ResultPublisher`, `CredentialStore`). Zero dependencies on anything except Kotlin stdlib and `java.time`.

This module is the future Kotlin Multiplatform common module. It must not import any JVM-specific library that doesn't have a KMP equivalent.

### engine/ — Measurement Core

| Sub-module | Responsibility | Spec file |
|------------|---------------|-----------|
| `twamp/` | RFC 5357 TWAMP controller + responder. Clean-room implementation. TCP control via NIO Selector. One sender thread per session. | `modules/twamp-engine.md` |
| `traceroute/` | Multi-mode traceroute via JNI (ICMP, UDP). Structured hop results. | `modules/traceroute.md` |
| `asn/` | MaxMind GeoLite2-ASN MMDB local database lookup. Optional — gracefully degrades if DB not present. | `modules/asn-resolver.md` |
| `pathchange/` | ASN path comparison. Detects route changes by comparing current vs previous deduplicated ASN path. | `modules/path-change-detector.md` |
| `sla/` | SLA profile evaluation. Green/yellow/red scoring against thresholds. | `modules/sla-evaluator.md` |

The engine exposes a single implementation of `MeasurementEngine` that composes all sub-modules.

### native/ — JNI C Library

| File | Responsibility | Heritage |
|------|---------------|----------|
| `twampUdp.c` | Raw POSIX UDP sockets. `sendPacket()`, `recvPacket()` with ancillary data (TTL via `IP_RECVTTL`, TOS via `IP_RECVTOS`). IPv4 + IPv6. DSCP setting. | RFC 5357 implementation |
| `traceroute.c` | ICMP echo and UDP probe sockets with incrementing TTL. Returns structured results (hop IP, RTT, TTL) to Kotlin. | New — replaces Python's subprocess wrapping |

Compiled to `libslogr-native.so` (Linux amd64/arm64), `libslogr-native.dylib` (macOS), `libslogr-native.dll` (Windows).

**JNI library loading:** The native lib is pre-installed to `/opt/slogr/lib/` in AMI and Docker images. For standalone installs, it's bundled in the JAR and extracted to a configurable directory (default `/opt/slogr/lib/`, override with `SLOGR_NATIVE_DIR` or `-Dslogr.native.dir=`). Never extract to `/tmp` — enterprise environments mount it with `noexec`.

**Capabilities:** The native library requires `CAP_NET_RAW` for ICMP traceroute and DSCP/TOS socket options. The systemd service file and Docker container must grant this capability.

### platform/ — Platform Shell

| Sub-module | Responsibility | Spec file |
|------------|---------------|-----------|
| `cli/` | Argument parsing. Commands: `check`, `daemon`, `connect`, `disconnect`, `status`, `version`, `setup-asn`. Flags per the CLI spec. | `cli/cli-interface.md` |
| `scheduler/` | Manages the test schedule. Runs sessions at configured intervals. Handles `set_schedule` command updates. | `modules/scheduler.md` |
| `rabbitmq/` | RabbitMQ publisher with JWT auth. Topic exchange `slogr.measurements`, routing keys `agent.{agent_id}.twamp/traceroute/health`. Publisher confirms. | `integration/rabbitmq-publisher.md` |
| `pubsub/` | GCP Pub/Sub pull subscriber. Polls `slogr.agent-commands.{agent_id}`. Dispatches to command handlers. | `integration/pubsub-subscriber.md` |
| `registration/` | Bootstrap token registration (`POST /api/v1/agents/register`). Interactive `slogr connect` flow. | `integration/registration.md` |
| `otlp/` | OTLP/HTTP metric exporter. Sends to `SLOGR_ENDPOINT` when set. Uses metric names from `data-model.md`. | `integration/otlp-exporter.md` |
| `buffer/` | Local write-ahead log. Write before publish. Mark after ACK. Replay on reconnect. Bounded size with oldest-first eviction. | `modules/local-buffer.md` |
| `health/` | Generates `HealthSnapshot` periodically. Tracks failure counters, buffer state, last success timestamps. | `modules/health-reporter.md` |
| `credential/` | Encrypts and stores `AgentCredential` on local disk. Loads on startup. Implements `CredentialStore` interface. | `security/credential-management.md` |

### app/ — Main Entry Point

Wires all modules together. Parses CLI args, decides mode (check/daemon/connect/etc.), instantiates the engine and platform components, and runs.

```kotlin
fun main(args: Array<String>) {
    val cli = CliParser.parse(args)
    when (cli.command) {
        Command.CHECK    -> runCheck(cli)
        Command.DAEMON   -> runDaemon(cli)
        Command.CONNECT  -> runConnect(cli)
        Command.DISCONNECT -> runDisconnect(cli)
        Command.STATUS   -> runStatus(cli)
        Command.VERSION  -> runVersion()
        Command.SETUP_ASN -> runSetupAsn(cli)
    }
}
```

## Key Architectural Rules

1. **`contracts/` has zero dependencies** on other modules. Everything depends on it.
2. **`engine/` never imports `platform/`**. It receives config as function arguments and returns data objects. It does not know about RabbitMQ, Pub/Sub, CLI, or OTLP.
3. **`platform/` calls `engine/` only through `MeasurementEngine`**. It can be tested with a mock engine.
4. **`app/` is the only module that knows about all other modules**. It's the composition root.
5. **No global mutable state**. All state is held in explicitly constructed objects passed via constructors.
6. **No hardcoded credentials anywhere**. Credentials come from `CredentialStore` (disk), environment variables (bootstrap token), or interactive input (`slogr connect`).
