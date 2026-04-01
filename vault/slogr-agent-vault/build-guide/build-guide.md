---
status: locked
version: 1.0
depends-on:
  - architecture/module-map
---

# Project Setup

## Gradle Multi-Module Configuration

```kotlin
// settings.gradle.kts
rootProject.name = "slogr-agent"
include("contracts", "engine", "native", "platform", "app")
```

```kotlin
// build.gradle.kts (root)
plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
}

subprojects {
    group = "io.slogr"
    version = "1.0.0-SNAPSHOT"
    repositories {
        mavenCentral()
    }
}
```

## Key Dependencies

| Library | Module | Purpose |
|---------|--------|---------|
| `kotlinx-serialization-json` | contracts | JSON serialization |
| `kotlinx-coroutines-core` | engine, platform | Async/concurrency |
| `kotlinx-datetime` | contracts | Cross-platform timestamps |
| `com.rabbitmq:amqp-client:5.x` | platform | RabbitMQ client |
| `com.google.cloud:google-cloud-pubsub:1.x` | platform | Pub/Sub client |
| `com.maxmind.geoip2:geoip2:4.x` | engine | MaxMind MMDB reader |
| `io.opentelemetry:opentelemetry-exporter-otlp:1.x` | platform | OTLP/HTTP export |
| `com.github.ajalt.clikt:clikt:4.x` | platform | CLI argument parsing |
| `ch.qos.logback:logback-classic:1.x` | app | Logging |
| `io.mockk:mockk:1.x` | test | Kotlin mocking |
| `org.testcontainers:rabbitmq:1.x` | test | Integration testing |
| `org.junit.jupiter:junit-jupiter:5.x` | test | Test framework |

## Java Version

Target: JDK 21 (Amazon Corretto). Use `jvmToolchain(21)` in Gradle.

---
---

# Build Phases

## Ordered build plan for Claude Code. Each phase is independently testable.

### Phase 0: Scaffolding (Day 1)

Set up the Gradle multi-module project, directory structure, CI pipeline skeleton, and empty module classes. Verify it compiles.

**Deliverable:** Empty project that builds and runs `slogr-agent version`.

### Phase 1: Contracts (Day 1-2)

Implement all data classes and interfaces from `architecture/data-model.md`. Add JSON serialization. Add unit tests for serialization round-trips.

**Deliverable:** `contracts/` module with all data classes, interfaces, and serialization tests passing.

### Phase 2: JNI Layer (Day 2-5)

Implement `twampUdp.c` for RFC 5357 UDP operations (reference Java agent for protocol understanding). Add `traceroute.c` with ICMP and UDP probe functions. Write Kotlin JNI wrapper (`SlogrNative.kt`). Compile for Linux amd64. Test with simple send/receive.

**Deliverable:** `libslogr-native.so` that can send/receive UDP packets and run ICMP traceroute probes from Kotlin. Unit tests passing on Linux.

**IMPORTANT:** Before writing any TWAMP code, read the Java agent source files:
- `libudp/src/main/c/jni/twampUdp.c` — the C reference for protocol understanding
- `common/src/.../twamp/util/TwampUdp.java` — the Java JNI interface (reference for function signatures)

### Phase 3: TWAMP Engine (Day 5-12)

Implement the TWAMP controller and responder from the RFC 5357 specification. Reference the Java agent for protocol understanding. This is the largest phase.

**Sub-phases:**
1. **Packet models** — Implement all RFC 5357 packet types (reference `common/src/.../model/` for protocol understanding). Fix bugs BUG-A through BUG-E.
2. **Control session** — Implement TCP control sessions using NIO Selector pattern. Fix FIX-1 (count ceiling), FIX-2 (StopSessions), FIX-3 (encIV removal).
3. **Sender** — Implement UDP sender. Both fixed-interval and Poisson timing.
4. **Reflector** — Implement UDP reflector with IP whitelist.
5. **Authentication** — Implement TWAMP authentication per RFC 4656 §3.1. PBKDF2, AES-CBC, HMAC-SHA1.
6. **Metrics assembly** — Implement metrics assembly → produce `MeasurementResult`.

**Deliverable:** Working TWAMP controller + responder. Can run `slogr-agent check <target>` with TWAMP only (no traceroute yet). Unit tests for all packet types. Integration test: controller ↔ responder in same JVM.

**IMPORTANT:** Read these Java files before starting:
- `controller/src/.../TwampClient.java`
- `controller/src/.../TwampSessionSender.java`
- `controller/src/.../TwampControlSession.java`
- `responder/src/.../TwampServer.java`
- `responder/src/.../TwampSessionReflector.java`
- `common/src/.../util/KeyStore.java`
- `common/src/.../util/TimeUtil.java`

### Phase 4: Traceroute + ASN + Path Change (Day 12-16)

Implement traceroute via JNI, MaxMind ASN lookup, path change detection, SLA evaluator.

**Deliverable:** `slogr-agent check <target> --traceroute` works end-to-end. ASN enrichment when DB present. Path change detection on repeated runs.

### Phase 5: CLI + Local Operation (Day 16-19)

Implement CLI parser, all commands, output formatters (text + JSON), `slogr-agent setup-asn`, `slogr-agent status`, `slogr-agent version`.

**Deliverable:** Full CLI works in disconnected mode. `check` and `daemon` produce correct output. OTLP export works when `SLOGR_ENDPOINT` is set.

### Phase 6: Connected Mode (Day 19-25)

Implement registration (bootstrap + connect), RabbitMQ publisher with JWT auth, Pub/Sub subscriber, all 5 command handlers, WAL, health reporter, credential store.

**Deliverable:** Full agent works in connected mode. Can register, receive commands, publish to RabbitMQ, report health. WAL replays on reconnect.

### Phase 7: Packaging (Day 25-28)

Docker image (multi-arch), RPM, DEB, systemd service file, AMI Packer template, CloudFormation template.

**Deliverable:** All packaging artifacts build in CI. Docker image runs. AMI boots and registers.

### Phase 8: Testing + Hardening (Day 28-32)

Load tests, security tests, contract tests. Fix any issues found. Final review.

**Deliverable:** All tests pass. Agent is production-ready for R1 deployment.

---
---

# Claude Code Instructions

## How to Use This Vault

1. **Start every session** by reading `README.md` and `architecture/system-overview.md`
2. **Before implementing a module**, read its spec file in `modules/` or `integration/`
3. **Before writing any TWAMP code**, read the corresponding Java source files in `agent-java/code-extract/`
4. **Follow the build phases in order** — each phase builds on the previous
5. **Run tests after each phase** before moving to the next
6. **Commit after each phase** with a clear message: `Phase N: <description>`

## Rules

1. **Never hardcode credentials.** Not in source, not in tests, not in config files.
2. **Never use `verify=false`** on any HTTP or TLS connection.
3. **Never invoke a shell.** No `Runtime.exec(String)`, no `ProcessBuilder` with shell. All external calls via JNI or HTTP client.
4. **Validate all inputs** at the boundary, before they reach engine code.
5. **Use Kotlin idioms.** Data classes, sealed classes, coroutines, extension functions. Don't write Java in Kotlin syntax.
6. **Keep contracts/ free of JVM-specific imports.** This module will become KMP common code.
7. **Fix all Java bugs listed in twamp-engine.md** — don't repeat them in the Kotlin implementation.
8. **Test against the Java responder** when possible — run the Java agent's responder and point the Kotlin controller at it to verify interoperability.

## Reference Files in agent-java/

The Java agent source is at `agent-java/code-extract/`. Key directories:

```
code-extract/
├── common/src/.../twamp/
│   ├── config/          ← AdminAction, KeyChain, MeasurementAgent
│   ├── model/           ← ALL RFC 5357 packet types — reference for protocol understanding
│   └── util/            ← TimeUtil, TwampUdp (JNI wrapper), KeyStore
├── libudp/src/main/c/jni/
│   ├── twampUdp.c       ← C reference for protocol understanding — implement in native/
│   └── twampUdp.h
├── controller/src/.../
│   ├── TwampClient.java           ← NIO Selector TCP control
│   ├── TwampSessionSender.java    ← UDP sender (thread per session)
│   ├── SessionScheduler.java      ← Reference for scheduling logic
│   └── TwampRestController.java   ← Reference for admin API
├── responder/src/.../
│   ├── TwampServer.java           ← TCP control listener
│   ├── TwampSessionReflector.java ← UDP reflector
│   └── TwampWhiteList.java        ← IP allowlist
├── grpc/src/main/proto/
│   └── TwampMetric.proto          ← Report schema reference
└── sample-json/                   ← Config payload examples
```
