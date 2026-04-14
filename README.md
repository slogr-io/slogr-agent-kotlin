# slogr-agent-kotlin

Layer 1 agent for the Slogr platform. Kotlin/JVM TWAMP (RFC 5357) measurement agent that tests network quality between cloud regions and publishes results to RabbitMQ.

## What It Does

- **TWAMP measurements:** RTT, jitter, packet loss (forward + reverse + ground-truth)
- **Traceroute:** Multi-mode (ICMP → TCP/443 → UDP) with ASN enrichment and path-change detection
- **SLA grading:** 27+ application profiles (VoIP, gaming, streaming, PCoIP, etc.)
- **Zero-config ASN:** Bundled ip2asn database with 5-tier download chain

## Tech Stack

- **Language:** Kotlin 2.1 / JVM 21 (Amazon Corretto)
- **Build:** Gradle multi-module (contracts, engine, platform, app, native, desktop)
- **Native:** JNI C library for UDP sockets and traceroute
- **Publish:** RabbitMQ (connected mode) or local SQLite (disconnected mode)
- **CLI:** Clikt (no Spring Boot)

## Quick Start

```bash
./gradlew build                    # Full build + tests
./gradlew :engine:test             # Single module tests
./gradlew :app:shadowJar           # Fat JAR
java -jar app/build/libs/slogr-agent-all.jar version
java -jar app/build/libs/slogr-agent-all.jar check 8.8.8.8
```

## Module Map

```
contracts  (data classes, zero deps)
    ↑
engine     (TWAMP, traceroute, ASN, SLA)
    ↑
platform   (CLI, RabbitMQ, Pub/Sub, OTLP)
    ↑
app        (composition root, shadow JAR)
```

Rule: `engine` never imports `platform`. Interfaces cross via `contracts`.

## Docker

```bash
docker run --network host --sysctl net.ipv4.ip_unprivileged_port_start=862 \
  ghcr.io/slogr-io/agent:latest daemon --rabbitmq-host mq.slogr.io
```

## Documentation

- [Vault specs](vault/) — authoritative agent specification (frozen R2 archive)
- [CHANGELOG](CHANGELOG.md) — release history
- [Contributing](https://github.com/nasimkz/slogr-cicd/blob/main/docs/CONTRIBUTING.md)
