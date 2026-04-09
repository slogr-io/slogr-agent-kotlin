# Slogr Desktop Agent — Layer 1.1 Vault

**Version:** 1.1.0 (build 11)
**Date:** April 8, 2026
**Status:** Shipped — standalone mode functional. Enterprise/SaaS connectivity pending.
**Repo:** github.com/slogr-io/slogr-agent-kotlin (branch: `desktop-agent`)

---

## Current Build State

| Feature | Status |
|---------|--------|
| Light theme UI (sidebar + dashboard + settings) | Shipped |
| Per-traffic-type TWAMP with real DSCP marking | Shipped |
| 8 traffic types with traffic signatures | Shipped |
| Progressive dashboard (grey → green/yellow/red) | Shipped |
| Accordion settings (Traffic Types, Servers, Application, About) | Shipped |
| Collapsible traffic type selection (3 active, expandable) | Shipped |
| Active server dropdown (one server tested at a time) | Shipped |
| User-added servers (runtime, no hardcoded) | Shipped |
| Compose tray popup (replaces broken AWT PopupMenu) | Shipped |
| SQLite 24h local history | Shipped |
| History sparkline chart | Shipped |
| Share Results (zip export: last 20 tests + diagnostics + system info) | Shipped |
| Auto-updater (checks slogr.io/desktop/update.json, silent fail) | Shipped |
| Traceroute (off by default, opt-in with disclaimer) | Shipped |
| Auto-start on login (Windows registry) | Shipped |
| Desktop notifications (grade changes) | Shipped |
| AES-256-GCM encrypted key storage | Shipped |
| Diagnostics (DNS, HTTPS, TWAMP port checks) | Shipped |
| MSI installer with Slogr icon | Shipped |
| Window + tray icon (Slogr "S" favicon) | Shipped |
| Enterprise/SaaS connectivity (API key, RabbitMQ, registration) | **Not wired** |
| OAuth sign-in flow | **Not built** |
| Auto-discovery of Slogr mesh reflectors | **Not built** |

---

## What Layer 1.1 Is

A desktop application for Windows (and future macOS) that runs the Slogr TWAMP measurement engine with a graphical user interface. Same engine as the server agent, wrapped in a Compose Desktop window with system tray.

**Key differentiator:** Per-traffic-type testing with real DSCP marking. The app sends 3 separate TWAMP sessions per measurement cycle — each with the actual packet size, interval, and DSCP value of the traffic type (VoIP=EF/DSCP46, Gaming=AF41/DSCP34, etc.). Results show how the ISP treats each traffic class independently.

---

## Traffic Signatures

Each traffic type sends a unique TWAMP session that mimics real application traffic:

| Type | Packets | Interval | Size | DSCP | Simulates |
|------|---------|----------|------|------|-----------|
| General Internet | 50 | 50ms | 1500B | 0 (BE) | Web browsing, downloads |
| Gaming | 33 | 30ms | 120B | 34 (AF41) | Game state updates at 33 tick/s |
| VoIP / Video Calls | 50 | 20ms | 200B | 46 (EF) | G.711 codec at 50pps |
| Streaming | 20 | 50ms | 1200B | 36 (AF42) | Video chunks |
| Cloud / SaaS | 30 | 50ms | 1500B | 32 (CS4) | API calls, SaaS apps |
| Remote Desktop | 33 | 30ms | 500B | 26 (AF31) | RDP/VDI screen updates |
| IoT / Telemetry | 10 | 100ms | 100B | 0 (BE) | Sensor data bursts |
| Financial Trading | 50 | 10ms | 64B | 46 (EF) | Ultra-low-latency trading |

Test time: ~3 seconds per type × 3 active types = **~9 seconds per cycle**.

---

## Architecture

```
Desktop App (Compose Desktop + Kotlin JVM)
├── UI Layer
│   ├── Main Window (sidebar: Dashboard + Settings)
│   ├── Dashboard (3 traffic type cards + history chart)
│   ├── Settings (accordion: Traffic Types, Servers, Application, About)
│   └── Tray (Compose popup window, AWT TrayIcon)
├── Core Layer
│   ├── ProfileManager (8 traffic types, select 3, DSCP signatures)
│   ├── DesktopMeasurementScheduler (per-type sequential sessions)
│   ├── DesktopAgentViewModel (progressive per-type grades)
│   ├── DesktopSettingsStore (JSON persistence)
│   ├── EncryptedKeyStore (AES-256-GCM)
│   ├── DesktopStateManager (ANONYMOUS/REGISTERED/CONNECTED)
│   ├── LocalHistoryStore (SQLite, 24h retention)
│   ├── HistoryPruner (hourly cleanup)
│   ├── ResultsExporter (zip: tests + diagnostics + system info)
│   ├── AutoUpdater (checks slogr.io, downloads MSI, applies on restart)
│   ├── AutoStartManager (Windows registry / macOS LaunchAgent)
│   └── DesktopNotifier (grade change notifications)
├── Engine (reused from L1 server agent)
│   ├── MeasurementEngineImpl (pure-Java mode, JavaUdpTransport)
│   ├── TwampController + TwampReflector
│   ├── SlaEvaluator + ProfileRegistry
│   └── TracerouteOrchestrator (ProcessBuilder, off by default)
└── Platform (reused from L1 server agent — NOT YET WIRED)
    ├── RabbitMqPublisher (CONNECTED mode)
    ├── HealthReporter (CONNECTED mode)
    ├── ApiKeyRegistrar (registration)
    └── OtlpExporter (REGISTERED mode)
```

---

## What the Desktop App Reuses from L1

| Module | Source | Notes |
|---|---|---|
| TWAMP controller + reflector | `engine/twamp/` | Pure-Java fallback mode (JavaUdpTransport) |
| DSCP marking | `native/JavaUdpTransport.setTos()` | Uses `DatagramSocket.setTrafficClass()` |
| SLA evaluation | `engine/sla/` | All 24 profiles from profiles.json |
| Traceroute | `engine/traceroute/` | ProcessBuilder wrapping OS `tracert`/`traceroute` |
| ASN resolution | `engine/asn/` | NullAsnResolver (MaxMind MMDB not bundled yet) |
| Data classes / contracts | `contracts/` | Shared with server agent |
| RabbitMQ publisher | `platform/rabbitmq/` | Exists but not wired in desktop Main.kt |
| Health reporter | `platform/health/` | Exists but not wired |
| API key registration | `platform/registration/` | Exists but not wired |
| OTLP exporter | `platform/otlp/` | Exists but not wired |

---

## What the Desktop App Adds (New)

| Module | Purpose | Status |
|---|---|---|
| Light theme Compose UI | Sidebar window with Dashboard + accordion Settings | Shipped |
| Per-type TWAMP scheduler | 3 sequential sessions with real traffic signatures | Shipped |
| Compose tray popup | Right-click menu as borderless Compose window | Shipped |
| Traffic type manager | 8 types, select 3, per-type DSCP + packet config | Shipped |
| Local history (SQLite) | 24h result storage, sparkline chart, export | Shipped |
| Share Results | Zip export of last 20 tests + diagnostics | Shipped |
| Auto-updater | Silent check + download from slogr.io | Shipped |
| Server management | User-added servers, active server dropdown | Shipped |
| Desktop packaging | MSI with Slogr icon, auto-start, jpackage | Shipped |
| Enterprise/SaaS connectivity | API key entry, RabbitMQ, registration | **v1.2.0** |

---

## Enterprise / SaaS Connectivity — What's Missing (v1.2.0)

### Existing Code (built, not wired)

| Component | Location | What it does |
|-----------|----------|-------------|
| `DesktopStateManager` | `desktop/core/state/` | Detects `sk_free_*` / `sk_live_*` keys, sets ANONYMOUS/REGISTERED/CONNECTED state |
| `EncryptedKeyStore` | `desktop/core/settings/` | AES-256-GCM encrypted API key storage on disk |
| `RabbitMqPublisher` | `platform/rabbitmq/` | Publishes measurement bundles to RabbitMQ exchange |
| `HealthReporter` | `platform/health/` | Sends agent health signals every 60 seconds |
| `ApiKeyRegistrar` | `platform/registration/` | Calls `POST /v1/agents` to register agent |
| `OtlpExporter` | `platform/otlp/` | Exports metrics via OpenTelemetry (REGISTERED mode) |
| `SLOGR_API_KEY` env var | `DesktopStateManager` | Reads key from environment (for SCCM/Intune deployment) |
| MSI `SLOGR_API_KEY` property | `build.gradle.kts` | IT admin can bake key into installer: `msiexec /i Slogr.msi /qn SLOGR_API_KEY=sk_live_...` |

### What Needs Building

| # | Item | Where | Effort | Description |
|---|------|-------|--------|-------------|
| 1 | **Account section in Settings** | `desktop/ui/` | 2h | Text field for API key entry, "Connect" button, status display, "Disconnect" button |
| 2 | **Wire RabbitMQ publisher** | `desktop/Main.kt` | 2h | When state=CONNECTED, start RabbitMQ publisher in scheduler result callback |
| 3 | **Wire agent registration** | `desktop/Main.kt` | 1h | On state transition to CONNECTED, call `POST /v1/agents` or `/api/claim-agents` |
| 4 | **Wire health reporter** | `desktop/Main.kt` | 30m | Start HealthReporter when CONNECTED |
| 5 | **Bootstrap endpoint (Enterprise)** | `slogr-enterprise/code/` | 2h | New `GET /api/bootstrap?key=sk_live_...` returns RabbitMQ host/port/credentials + reflector list |
| 6 | **Bootstrap endpoint (SaaS)** | `slogr-app/` BFF | 2h | Same as above but for SaaS (`POST /v1/pair` or similar) |
| 7 | **Pairing code flow (SaaS, optional)** | `slogr-app/` + `desktop/` | 4h | SaaS generates short code (e.g., `SLOGR-7K2M-9X4P`), user enters in desktop, exchanges for API key |
| 8 | **Auto-populate reflectors on connect** | `desktop/` | 1h | Bootstrap response includes reflector list → auto-add to servers |

### Enterprise Deployment Flow (after v1.2.0)

```
IT Admin:
1. msiexec /i Slogr.msi /qn SLOGR_API_KEY=sk_live_abc123 SLOGR_SERVER=https://slogr.enterprise.com
2. App auto-starts on login
3. Reads SLOGR_API_KEY from env → state = CONNECTED
4. Calls SLOGR_SERVER/api/bootstrap → gets RabbitMQ credentials + reflector list
5. Starts measuring + publishing to Enterprise RabbitMQ
6. Agent appears in Enterprise Agent Directory
```

### SaaS User Flow (after v1.2.0)

```
User:
1. Signs up at app.slogr.io → gets dashboard
2. Clicks "Add Desktop Agent" → gets pairing code or API key
3. Downloads Slogr Desktop from slogr.io/download
4. Opens app → Settings → Account → enters key or pairing code
5. App connects → starts publishing to SaaS
6. User sees laptop connectivity on SaaS dashboard from anywhere
```

### MVP Shortcut (no pairing code needed)

Skip items #7. Use direct API key entry:
1. SaaS dashboard shows user's API key (`sk_live_abc123`)
2. User copies and pastes into desktop app Settings → Account
3. App connects

This is the Stripe/Datadog pattern. Pairing code is nicer UX but not essential.

---

## Update Notification Mechanism

The desktop agent checks for updates but **never downloads or installs anything automatically** (supply chain security — see ADR-068).

1. On startup + every 24 hours, fetches `https://slogr.io/desktop/update.json`
2. If 404 or error → silently ignored, no banner shown
3. If valid JSON with newer version → shows **persistent green banner** at top of window:
   "A new version of Slogr is available that improves performance. Click here to download."
4. Banner cannot be dismissed — stays until user updates or update is no longer available
5. Clicking the banner opens the download URL in the system browser
6. User downloads and installs manually (standard MSI upgrade, settings preserved)

**Update check URL:** `https://slogr.io/desktop/update.json`

**update.json format:**
```json
{
  "version": "1.2.0",
  "download_url": "https://slogr.io/desktop/Slogr-1.2.0.msi"
}
```

**Security:** The agent never writes executables to disk. No automatic code execution. The user's browser handles the download over HTTPS from the verified slogr.io domain.

---

## Vault Structure

```
slogr-desktop-vault/
├── README.md                           ← you are here
├── architecture/
│   ├── system-overview.md              ← L1.1 position in the stack
│   ├── decisions-log-l11.md            ← ADRs 050-064
│   └── compose-desktop.md             ← UI architecture, threading, lifecycle
├── modules/
│   ├── local-history.md                ← SQLite schema, 24h retention, export
│   ├── reflector-discovery.md          ← runtime server config (user-added)
│   └── profile-manager.md             ← 8 traffic types, 3 active, DSCP signatures
├── integration/
│   ├── desktop-registration.md         ← three-state model (not yet wired)
│   └── desktop-oauth.md               ← sign-in flow (not yet built)
├── ui/
│   ├── main-window.md                  ← light theme, sidebar, accordion settings
│   └── system-tray.md                  ← Compose popup, icon states
├── packaging/
│   └── desktop-packaging.md            ← MSI, icon, auto-start, auto-update
└── build-guide/
    └── build-guide-l11.md              ← build phases
```

---

## Key ADRs

| ADR | Title | Summary |
|-----|-------|---------|
| 050 | Compose Desktop for GUI | JetBrains Compose, cross-platform, bundled JRE |
| 051 | Pure-Java Fallback Mode | No JNI on desktop, DatagramSocket for TWAMP |
| 055 | Close = Minimize to Tray | App stays running in background |
| 056 | Auto-Start Default ON | Runs on login via Windows registry |
| 057 | 24h Local SQLite History | Local storage for all states |
| 060 | Traffic-Centric Dashboard | 3 traffic type cards, not location cards |
| 061 | Runtime Server Config | Zero hardcoded servers, all user-added |
| 062 | Minimal Tray Menu | 5 items max, Compose popup window |
| 063 | Light Theme | White bg, dark text, green accent |
| 064 | Compose Popup for Tray Menu | Replaces broken AWT PopupMenu |
