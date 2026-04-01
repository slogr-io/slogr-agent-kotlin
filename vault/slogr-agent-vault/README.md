# Slogr Agent — Kotlin Rewrite Vault

## Purpose

This vault is the authoritative specification for the Slogr Agent. Claude Code reads this vault before writing any agent code. Every architectural decision, module boundary, interface contract, and security requirement is defined here.

## What is the Slogr Agent?

A lightweight network measurement probe written in Kotlin/JVM. It runs TWAMP (RFC 5357) tests, traceroutes, and ASN path analysis across cloud networks. Deployed on EC2/GCE/Azure VMs, Docker, or bare metal. Targets: t3.micro (2 vCPU, 1 GB RAM), 512 MB memory ceiling.

One binary, two states:
- **Disconnected (free):** `slogr check` for one-shot tests, `slogr daemon` for continuous. Outputs to stdout and OTLP/HTTP. No backend contact.
- **Connected (Pro $10/month):** Same as above, plus registers with SaaS, publishes to RabbitMQ, receives commands via Pub/Sub.

## Vault Structure

```
slogr-agent-vault/
├── README.md                          ← You are here
├── architecture/
│   ├── system-overview.md             ← Start here. What the agent is and does.
│   ├── module-map.md                  ← All modules, interfaces, dependency graph
│   ├── concurrency-model.md           ← Thread pools, coroutines, shutdown
│   ├── data-model.md                  ← Core data classes and interfaces
│   ├── decisions-log.md               ← ADRs: why Kotlin, why JNI, etc.
│   └── extensibility.md              ← KMP future, plugin points
├── security/
│   ├── threat-model.md                ← Attack surface, trust boundaries
│   ├── credential-management.md       ← JWT, bootstrap tokens, storage
│   ├── input-validation.md            ← Every untrusted input boundary
│   ├── network-security.md            ← TLS, certificate handling, egress
│   └── anti-abuse.md                  ← Rate limiting, resource caps, anti-DDoS
├── modules/
│   ├── twamp-engine.md                ← TWAMP RFC 5357 implementation
│   ├── jni-native.md                  ← twampUdp.c + traceroute probes
│   ├── traceroute.md                  ← Multi-mode traceroute via JNI
│   ├── asn-resolver.md                ← Team Cymru whois + LRU cache
│   ├── path-change-detector.md        ← ASN path comparison
│   ├── sla-evaluator.md               ← Profile thresholds, scoring
│   ├── local-buffer.md                ← Write-ahead log, replay
│   ├── health-reporter.md             ← Agent health signals
│   └── scheduler.md                   ← Test scheduling, intervals
├── integration/
│   ├── rabbitmq-publisher.md          ← Data plane, JWT auth, routing keys
│   ├── pubsub-subscriber.md           ← Control plane, command polling
│   ├── registration.md                ← Bootstrap token + slogr connect
│   ├── command-handlers.md            ← 5 command types
│   └── otlp-exporter.md              ← OTLP/HTTP metrics, SLOGR_ENDPOINT
├── cli/
│   └── cli-interface.md               ← Commands, flags, output formats
├── packaging/
│   ├── marketplace-requirements.md    ← AWS/GCP/Azure compliance
│   ├── docker.md                      ← Multi-arch container image
│   ├── standalone-binary.md           ← yum/apt/brew install, systemd
│   └── resource-budget.md             ← Memory, CPU, disk, network on t3.micro
├── testing/
│   └── strategy.md                    ← Test pyramid, coverage, security tests
├── operations/
│   ├── logging.md                     ← Structured logging, redaction
│   └── upgrade-lifecycle.md           ← Binary upgrade, drain, swap
└── build-guide/
    ├── project-setup.md               ← Gradle structure, dependencies
    ├── build-phases.md                ← Ordered build plan for Claude Code
    └── claude-code-instructions.md    ← How to use this vault
```

## Reading Order for Claude Code

1. `architecture/system-overview.md` — what you're building
2. `architecture/data-model.md` — the domain objects everything revolves around
3. `architecture/module-map.md` — how modules connect
4. `build-guide/claude-code-instructions.md` — how to work in this repo
5. `build-guide/build-phases.md` — what to build in what order
6. Then read module specs as you build each phase.

## Relationship to SaaS Vault

This vault covers the agent binary only. For backend contracts, refer to the Slogr SaaS vault:
- `layer2/rabbitmq-topology.md` — exchange, queues, routing keys
- `layer2/clickhouse-schema.md` — what the agent's output must match
- `layer2/pubsub-config.md` — control plane topics
- `layer25/agent-registration.md` — registration flow
- `layer25/command-payloads.md` — command schemas
- `layer25/liveness-model.md` — health signal expectations
- `architecture/data-flow.md` — end-to-end data path

## Heritage

The Kotlin agent is a clean-room RFC 5357 implementation. The Java agent was studied for protocol understanding; the Python agent was studied for traceroute/ASN patterns. The Kotlin agent replaces both prior implementations.
