# Slogr Agent Vault — R2 Addendum

**Version:** 2.0
**Date:** March 31, 2026
**Status:** Locked — any changes require team sign-off
**Relationship to R1 vault:** This vault layers on top of the R1 vault (`slogr-agent-vault/`). R1 specs remain authoritative unless explicitly overridden here. Where this document says "replaces," the R1 spec is superseded. Where it says "extends," the R1 spec remains valid and this adds to it.

---

## What R2 Is

R2 takes the working R1 agent (388 tests, 8 phases complete) and makes it production-ready for public release and fleet deployment at 200+ mesh agents. R1 is the foundation. R2 is the ship-ready product.

## R2 Goals

1. **Scale** — Thread pool reflector handles 1000+ concurrent inbound sessions per mesh agent
2. **Accuracy** — Virtual clock estimation for one-way delay when NTP sync is unavailable
3. **PLG** — Three-state agent model (Anonymous → Registered → Connected), OTLP gate, CLI nudge
4. **Registration** — API key model replaces bootstrap tokens. One key per tenant for mass deployment.
5. **Packaging** — 9 distribution formats covering every enterprise deployment tool
6. **Egress optimization** — Traceroute heartbeat/change-only publishing reduces egress ~80%
7. **Security** — Encrypted TWAMP mode (RFC 5357 mode=4). Elastic License 2.0 or BSL 1.1.

## Vault Structure

```
slogr-agent-vault-r2/
├── README.md                              ← this file
├── architecture/
│   ├── decisions-log-r2.md                ← new ADRs (021-030+)
│   ├── three-state-model.md               ← Anonymous / Registered / Connected
│   └── thread-pool-reflector.md           ← replaces R1 thread-per-session
├── modules/
│   ├── virtual-clock-estimator.md         ← clock sync detection + offset estimation
│   ├── encrypted-twamp.md                 ← AES-CBC mode=4
│   └── bidirectional-traceroute.md        ← path symmetry, both agents trace each other
├── integration/
│   ├── registration-r2.md                 ← REPLACES R1 registration-and-otlp.md
│   ├── otlp-gate.md                       ← SLOGR_API_KEY requirement for OTLP
│   └── push-config-r2.md                  ← extended payload (8 fields)
├── cli/
│   └── cli-r2.md                          ← daemon auto-connect, air-gap detection, footer nudge
├── packaging/
│   └── packaging-r2.md                    ← REPLACES R1 packaging.md (9 formats)
├── testing/
│   └── r2-test-matrix.md                  ← R2-specific test scenarios
├── build-guide/
│   └── build-guide-r2.md                  ← R2 build phases (6 phases)
└── operations/
    └── egress-optimization.md             ← traceroute heartbeat/change-only strategy
```
