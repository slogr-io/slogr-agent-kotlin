# Slogr Desktop Agent — Layer 1.1 Vault

**Version:** 1.1
**Date:** April 6, 2026
**Status:** Locked — any changes require team sign-off
**Repo:** github.com/nasimkz/slogr-agent-kotlin (same repo as L1, separate branch)
**Branch:** `desktop-agent`
**Relationship to L1 vault:** This vault is a branch of the L1 agent. It reuses the R1 `contracts/` and `engine/` modules and the R2 three-state model, API key registration, and operational hardening. Where this vault says "inherits," the L1 R1/R2 spec is authoritative. Where it says "new," this vault defines something that does not exist in L1.

---

## Repository Setup

The desktop agent lives in the same repo as the server agent, on a separate branch, as a separate Gradle subproject. This shares engine code without duplicating it.

### Step 1: Clone and Branch

```bash
git clone https://github.com/nasimkz/slogr-agent-kotlin.git
cd slogr-agent-kotlin
git checkout master
git pull origin master
git checkout -b desktop-agent
```

**All desktop work happens on the `desktop-agent` branch.** Never commit desktop code to `master`.

### Step 2: Create the Desktop Subproject

```
slogr-agent-kotlin/
├── app/                        ← EXISTING: CLI agent (DO NOT MODIFY)
├── contracts/                  ← EXISTING: shared data classes (DO NOT MODIFY)
├── engine/                     ← EXISTING: measurement engine (DO NOT MODIFY)
├── platform/                   ← EXISTING: RabbitMQ, Pub/Sub, OTLP (DO NOT MODIFY)
├── native/                     ← EXISTING: JNI C library (desktop does NOT use this)
├── desktop/                    ← NEW: your Compose Desktop subproject
│   ├── build.gradle.kts
│   └── src/main/kotlin/io/slogr/desktop/
│       ├── Main.kt
│       ├── ui/
│       ├── core/
│       └── scheduler/
├── vault/
│   └── slogr-desktop-vault/    ← NEW: copy this vault here
└── settings.gradle.kts         ← MODIFY: add include("desktop")
```

### Step 3: Add to settings.gradle.kts

Add this single line to the existing `settings.gradle.kts`:

```kotlin
include("desktop")
```

### Step 4: Create desktop/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.6.0"
    kotlin("plugin.serialization")
}

dependencies {
    // Reuse L1 modules — this is why we're in the same repo
    implementation(project(":contracts"))
    implementation(project(":engine"))
    implementation(project(":platform"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // SQLite (local history)
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // HTTP client (reflector discovery, OAuth)
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")

    // Testing
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "io.slogr.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
            )
            packageName = "Slogr"
            packageVersion = "1.1.0"
            description = "Slogr Network Quality Monitor"
            vendor = "Slogr"
            windows {
                menuGroup = "Slogr"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                shortcut = true
                dirChooser = true
            }
            macOS {
                bundleID = "io.slogr.desktop"
                appCategory = "public.app-category.utilities"
            }
        }
    }
}
```

### Step 5: Verify Shared Modules Compile

```bash
./gradlew :desktop:compileKotlin
```

This MUST succeed before writing any desktop code. If it fails, fix the dependency declarations — do not modify the existing modules.

### Step 6: Verify L1R2 Tests Still Pass

```bash
./gradlew :app:test
```

This must still pass. If the desktop subproject breaks existing tests, something is wrong with the Gradle configuration.

### Git Discipline

- One commit per logical unit of work
- Push after every completed phase: `git push origin desktop-agent`
- Report after each phase: "Phase N complete — X tests passing"
- Never merge into `master` until reviewed
- Periodically rebase on `master` to pick up L1R2 updates: `git checkout desktop-agent && git rebase master`

### Critical Rule: DO NOT MODIFY FILES OUTSIDE `desktop/`

The `app/`, `contracts/`, `engine/`, `platform/`, and `native/` directories belong to L1R2. Import them, depend on them — never edit them. If you need a change in an engine module, flag it. Do not make the change yourself.

---

## What Layer 1.1 Is

A desktop application for Windows and macOS that runs the Slogr measurement engine with a graphical user interface. Same TWAMP engine, same traceroute, same SLA evaluation — wrapped in a Compose Desktop window with a system tray icon.

It is NOT a separate product. It is the same `slogr-agent` measurement core with a GUI shell. A desktop agent that transitions to CONNECTED mode appears in the SaaS Agent Directory alongside server agents. It publishes to the same RabbitMQ exchange, receives the same Pub/Sub commands, and lands data in the same ClickHouse tables.

## Who Uses It

- **Gamers** monitoring connection quality during play sessions
- **Remote workers** on VoIP/video calls wanting to know if their internet is the problem
- **Network engineers** running tests from their workstation
- **IT admins** deploying across VDI/desktop fleets via SCCM/Intune/Jamf
- **Enterprise users** linked to an organizational tenant

## How It Maps to the Three-State Model

| Desktop State | Agent State | Key | Experience |
|---|---|---|---|
| Fresh install, no sign-in | ANONYMOUS | None | Measures against free-tier Slogr reflectors. Results shown locally. 24h SQLite history. No OTLP, no SaaS. |
| User signs in, gets free key | REGISTERED | `sk_free_*` | Same as above + OTLP export enabled. Lead tracked in SaaS. |
| User upgrades to Pro | CONNECTED | `sk_live_*` | Full SaaS: RabbitMQ, Pub/Sub commands, investigation, alerts, history. Agent visible in Agent Directory. $10/agent/month add-on. |

## What the Desktop App Reuses from L1

| Module | Source | Notes |
|---|---|---|
| TWAMP controller + responder | `engine/twamp/` | Identical. Pure-Java fallback mode (no JNI on desktop). |
| Traceroute | `engine/traceroute/` | ProcessBuilder wrapping `tracert` (Windows) or `traceroute -U` (macOS UDP mode). |
| ASN resolution | `engine/asn/` | Same MaxMind MMDB, same resolver. |
| Path change detection | `engine/pathchange/` | Identical. |
| SLA evaluation | `engine/sla/` | Identical. All 27+ profiles. |
| Deduplication | `engine/dedup/` | Identical. |
| Data classes / contracts | `contracts/` | Identical. Future KMP common module. |
| RabbitMQ publisher | `platform/rabbitmq/` | CONNECTED mode only. Identical. |
| Pub/Sub subscriber | `platform/pubsub/` | CONNECTED mode only. Identical. |
| OTLP exporter | `platform/otlp/` | REGISTERED + CONNECTED. Identical with R2 gate. |
| WAL buffer | `platform/wal/` | CONNECTED mode only. Identical with R2 bounded eviction. |
| Health reporter | `platform/health/` | CONNECTED mode only. Identical. |
| API key registration | `integration/registration/` | Same `POST /v1/agents` endpoint, same `GET /v1/keys/validate`. |
| Persistent fingerprint | `platform/identity/` | Same file-backed fingerprint (ADR-034). Windows path already defined. |
| Config watcher | `platform/config/` | Watches settings file for key changes. |

## What the Desktop App Adds (New)

| Module | Purpose |
|---|---|
| Compose Desktop UI | Main window, system tray, settings screens |
| Local history (SQLite) | 24h queryable result storage for ANONYMOUS/REGISTERED users |
| Reflector discovery | `GET /v1/reflectors` client, nearest-selection logic |
| Profile manager | User-selected SLA profile (Internet, Gaming, Streaming, VoIP) |
| Freemium gating | Enforce free-tier limits on profiles and locations |
| Desktop OAuth | Browser-based sign-in flow with localhost callback or custom URI scheme |
| Desktop packaging | `.msi` (Windows), `.dmg` (macOS) with Compose Desktop, auto-start |

## Vault Structure

```
slogr-desktop-vault/
├── README.md                           ← you are here
├── architecture/
│   ├── system-overview.md              ← L1.1 position in the stack, what it reuses
│   ├── decisions-log-l11.md            ← ADRs 040-049
│   └── compose-desktop.md             ← UI architecture, threading, lifecycle
├── modules/
│   ├── local-history.md                ← SQLite schema, 24h retention, query API
│   ├── reflector-discovery.md          ← runtime server config (user-added, no hardcoded)
│   └── profile-manager.md             ← 8 traffic types, select 3, freemium gating
├── integration/
│   ├── desktop-registration.md         ← three-state model applied to desktop UX
│   └── desktop-oauth.md               ← sign-in flow, token exchange, key storage
├── ui/
│   ├── main-window.md                  ← sidebar layout, Dashboard + Settings views
│   └── system-tray.md                  ← minimal tray menu, icon states, AWT PopupMenu
├── packaging/
│   └── desktop-packaging.md            ← MSI, DMG, jpackage, Conveyor, auto-start
└── build-guide/
    └── build-guide-l11.md              ← build phases for Claude Code
```

## Reading Order for Claude Code

1. This `README.md` — understand scope, reuse map, and **complete the Repository Setup section first**
2. `architecture/system-overview.md` — what you're building
3. `architecture/compose-desktop.md` — UI architecture
4. `ui/main-window.md` + `ui/system-tray.md` — what the user sees
5. `modules/reflector-discovery.md` — how targets are discovered
6. `modules/local-history.md` — local storage
7. `integration/desktop-registration.md` — state transitions
8. `build-guide/build-guide-l11.md` — build phases, mock setup, critical rules
9. Then read L1 R1 vault (`vault/slogr-agent-vault/`) for inherited module specs
10. Then read L1 R2 vault (`vault/slogr-agent-vault-r2/`) for three-state model and registration

## Relationship to Other Layers

| Layer | Interaction |
|---|---|
| L1 R1/R2 | Desktop app reuses engine, contracts, and platform modules. Same binary core. |
| L2 (Data Platform) | CONNECTED desktop agents publish to same RabbitMQ exchange, same ClickHouse tables. `src_cloud="residential"`, `src_region` from IP geolocation. |
| L2.5 (Control Plane) | CONNECTED desktop agents register via `POST /v1/agents`, receive Pub/Sub commands, appear in Agent Directory. |
| L3 (SaaS) | Desktop app opens SaaS in browser for charts/investigation. New endpoint: `GET /v1/reflectors`. OAuth via `slogr.io/keys`. |

## What Is NOT in This Vault

| Item | Status |
|---|---|
| Chrome extension | Parked — measurement model unresolved |
| Android/iOS SDK | Future — depends on KMP infrastructure |
| Slogr Proxy | R4 — separate binary, separate vault |
| Home Assistant plugin | Future |
| Enterprise tenant association | Deferred — design not locked |
