# Desktop Agent Build Rules

You are building the Slogr Desktop Agent (Layer 1.1).

## Vault Location
Read the vault at `vault/slogr-desktop-vault/` before writing any code.
Start with `vault/slogr-desktop-vault/README.md`.

## Critical Rules
1. All code goes in `desktop/` — NEVER modify files in `app/`, `contracts/`, `engine/`, `platform/`, or `native/`
2. Pure-Java mode only — do NOT load or reference JNI native libraries
3. All UI in Compose Desktop — no Swing, no JavaFX
4. Never block the Compose UI thread — all I/O on Dispatchers.IO
5. After every phase, run `./gradlew :app:test` to verify L1R2 still passes
6. ADR numbering for desktop starts at 050
7. Build in phase order per `vault/slogr-desktop-vault/build-guide/build-guide-l11.md`

## Shared Modules (read-only dependencies)
- `contracts/` — data classes, interfaces (import via `project(":contracts")`)
- `engine/` — TWAMP, traceroute, ASN, SLA evaluation (import via `project(":engine")`)
- `platform/` — RabbitMQ, Pub/Sub, OTLP, WAL, health (import via `project(":platform")`)
