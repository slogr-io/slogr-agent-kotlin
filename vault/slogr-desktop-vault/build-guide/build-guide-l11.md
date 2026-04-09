---
status: locked
version: 1.0
depends-on:
  - architecture/compose-desktop
  - All other vault files
---

# Build Guide — Layer 1.1 Desktop Agent

## Prerequisites

Before starting, the following must be complete:
- L1R2 agent code on `master` branch: `contracts/`, `engine/`, `platform/` modules built and tested
- Repository setup: cloned, `desktop-agent` branch created, `desktop/` subproject configured (see README.md "Repository Setup" section)
- `./gradlew :desktop:compileKotlin` succeeds
- `./gradlew :app:test` still passes (L1R2 tests are the regression baseline)

## External Dependencies (Can Be Mocked)

| Dependency | Status | How to handle |
|-----------|--------|---------------|
| `GET /v1/reflectors` (L3 BFF) | Not built yet | Hardcode mock response (see below) |
| LinkedIn OAuth at slogr.io | Not built yet | Skip Phase 5 until available. Phases 0-4 and 6-7 work in ANONYMOUS mode. |
| Mesh reflectors (real TWAMP) | Not deployed | Run local reflector: `java -jar app/build/libs/slogr-agent-all.jar daemon` |
| `is_public_reflector` column | Not in Cloud SQL yet | Mock only — not needed until real reflector discovery works |

### Mock Reflector for Development

Create a hardcoded mock in `ReflectorDiscoveryClient.kt` until the real endpoint exists:

```kotlin
private val MOCK_REFLECTORS = listOf(
    Reflector(
        id = "00000000-0000-0000-0000-000000000001",
        region = "local",
        cloud = "dev",
        host = "127.0.0.1",  // or your test VM IP
        port = 862,
        latitude = 0.0,
        longitude = 0.0,
        tier = "free"
    )
)

// Toggle: set to true when GET /v1/reflectors is deployed
private val USE_REAL_API = false
```

Run a local reflector on the same machine or a test VM:
```bash
java -jar app/build/libs/slogr-agent-all.jar daemon
```

The desktop app connects to it for TWAMP tests. Replace the mock with the real API call when `GET /v1/reflectors` is deployed.

## Critical Rules

1. **Pure-Java mode ONLY.** Never load the JNI native library. Use the `DatagramSocket`-based `UdpTransport` implementation. Set `native_mode = false` in registration.
2. **Never block the Compose UI thread.** All measurement work on `Dispatchers.IO`. All UI updates via `StateFlow` or `mutableStateOf`.
3. **All UI in Compose Desktop.** No Swing. No JavaFX. No AWT (except tray icon if Compose doesn't support it natively).
4. **Do not modify any file outside `desktop/`.** The `app/`, `contracts/`, `engine/`, `platform/`, and `native/` directories are L1R2 territory.
5. **ADR numbering starts at 050.** ADRs 001-040 are used by L1R1/R2. Desktop ADRs are 050-059.

## Build Phases

### Phase 0: Scaffolding (~2 days)

**Goal:** Compose Desktop project compiles and shows an empty window with a tray icon.

1. Create Gradle subproject `slogr-desktop` with Compose Desktop plugin
2. Add dependencies on L1 `contracts`, `engine`, `platform` modules
3. Create `Main.kt` with Compose `application {}` block
4. Show an empty `Window` with title "Slogr"
5. Show a `Tray` icon (grey, static)
6. Verify: app launches on Windows and macOS, tray icon visible, window shows

**Verify in browser/app:** Window opens. Tray icon appears. Close button hides window. Tray → Quit exits.

**Progress: 10%**

### Phase 1: Settings & State (~3 days)

**Goal:** Settings persistence, state determination, and profile selection work.

1. Create `DesktopSettings.kt` — read/write `settings.json`
2. Create `EncryptedKeyStore.kt` — OS-specific encrypted key storage
3. Create `DesktopStateManager.kt` — determine ANONYMOUS/REGISTERED/CONNECTED from settings + env var
4. Create `ProfileManager.kt` — profile selection, freemium gating
5. Create `FreemiumGate.kt` — enforce free/paid limits
6. Wire settings to Compose UI state via `StateFlow`
7. Build Settings window with all 5 sections (Account, Monitoring, Locations, Application, About)

**Verify:** Settings persist across restarts. Profile changes immediately. State displayed correctly in footer.

**Progress: 25%**

### Phase 2: Reflector Discovery & Selection (~2 days)

**Goal:** App discovers reflectors and selects nearest.

1. Create `ReflectorDiscoveryClient.kt` — HTTP GET `/v1/reflectors`
2. Create `ReflectorCache.kt` — local JSON cache with 24h TTL
3. Create `NearestSelector.kt` — haversine distance + optional latency probe
4. Wire to Settings → Locations section
5. Handle errors: network down, no cache, empty response

**Verify:** First launch discovers reflectors. Nearest 3 auto-selected. Cache survives restart. Offline uses cache.

**Progress: 35%**

### Phase 3: Measurement Engine Integration (~3 days)

**Goal:** App runs TWAMP tests against reflectors and shows results.

1. Create `DesktopMeasurementScheduler.kt` — interval-based, drives the L1 engine
2. Wire `TwampController` (pure-Java mode) to selected reflectors
3. Wire `TracerouteRunner` (ProcessBuilder) to selected reflectors
4. Wire `AsnResolver`, `PathChangeDetector`, `SlaEvaluator`
5. Create `DesktopAgentViewModel.kt` — collect results, compute grades, emit `StateFlow`
6. Update main window: grade badge, location cards, "Run Test Now" button
7. Update tray icon color based on worst grade

**Verify:** App measures against real Slogr reflectors (or local test reflector). Results appear in UI. Tray icon changes color based on grade.

**Progress: 55%**

### Phase 4: Local History (~2 days)

**Goal:** Results stored in SQLite, queryable, pruned after 24h.

1. Create `LocalHistoryStore.kt` — SQLite schema, CRUD operations
2. Create `HistoryPruner.kt` — hourly cleanup coroutine
3. Create `HistoryChart.kt` — sparkline composable reading from SQLite
4. Wire measurement results → SQLite writes
5. Wire SQLite reads → main window history section

**Verify:** History accumulates. Sparkline shows 24h of data. Old entries pruned. Database stays small.

**Progress: 65%**

### Phase 5: OAuth & Registration (~3 days)

**Goal:** User can sign in, get a free key, and transition to REGISTERED. Manual key entry for CONNECTED.

1. Create `DesktopOAuthFlow.kt` — orchestrates browser-based OAuth
2. Create `LocalCallbackServer.kt` — ephemeral localhost HTTP server
3. Create `CustomUriHandler.kt` — slogr:// URI handler
4. Create `PkceGenerator.kt` — PKCE code_verifier/challenge
5. Wire "Sign In" button → OAuth flow → key storage → state transition
6. Wire "Enter API Key" in settings → manual key entry → state transition
7. Create `DesktopRegistrar.kt` — wraps L1 `ApiKeyRegistrar` for CONNECTED auto-registration
8. Test: ANONYMOUS → REGISTERED (OAuth) → CONNECTED (manual key)

**Verify:** Sign in works with LinkedIn. Key stored encrypted. State transitions correctly. CONNECTED mode connects to RabbitMQ (if test broker available).

**Progress: 80%**

### Phase 6: Tray, Notifications & Polish (~2 days)

**Goal:** System tray fully functional, desktop notifications, grade transitions.

1. Wire tray context menu: all items functional
2. Create `DesktopNotifier.kt` — OS notification bridge
3. Implement grade-change notifications
4. Implement "Open Dashboard" deep link (CONNECTED only)
5. Implement auto-start toggle (writes/removes registry key or LaunchAgent plist)
6. Implement `--background` flag for tray-only launch
7. Implement diagnostics ("Run Diagnostics" in About section)

**Verify:** Tray menu works. Notifications fire on grade change. Auto-start works. Background launch works.

**Progress: 90%**

### Phase 7: Packaging & Distribution (~3 days)

**Goal:** Signed installers for Windows and macOS.

1. Configure `jpackage` or Conveyor in `build.gradle.kts`
2. Build MSI for Windows x64
3. Build DMG for macOS (universal or separate amd64/aarch64)
4. Test silent install: `msiexec /i slogr-desktop.msi /qn`
5. Test silent install with key: `msiexec /i ... SLOGR_API_KEY=sk_live_...`
6. Test macOS drag-to-Applications
7. Verify auto-start on both platforms
8. Verify URI scheme registration on both platforms
9. Code signing (Authenticode for Windows, Developer ID for macOS)
10. Set up CI pipeline (GitHub Actions)

**Verify:** Clean install on both platforms. Auto-start works. Silent install with key works. URI scheme works.

**Progress: 100%**

## Estimated Timeline

| Phase | Duration | Cumulative |
|---|---|---|
| Phase 0: Scaffolding | 2 days | 2 days |
| Phase 1: Settings & State | 3 days | 5 days |
| Phase 2: Reflector Discovery | 2 days | 7 days |
| Phase 3: Measurement Engine | 3 days | 10 days |
| Phase 4: Local History | 2 days | 12 days |
| Phase 5: OAuth & Registration | 3 days | 15 days |
| Phase 6: Tray & Polish | 2 days | 17 days |
| Phase 7: Packaging | 3 days | 20 days |
| **Total** | **~20 working days (~4 weeks)** | |

## Claude Code Instructions

1. Read this vault completely before writing any code.
2. Read the L1 R1 vault (`vault/slogr-agent-vault/`) for inherited module specs.
3. Read the L1 R2 vault (`vault/slogr-agent-vault-r2/`) for three-state model and registration.
4. Verify setup: `./gradlew :desktop:compileKotlin` must succeed before writing any code.
5. Build in phase order. Do not skip phases. Phase 5 (OAuth) may be skipped if L3R2 is not deployed — build it last.
6. After each phase, report percentage progress and what was built.
7. Pure-Java mode only — do not attempt to load JNI native libraries.
8. All UI on Compose Desktop — no Swing, no JavaFX.
9. All measurement work on `Dispatchers.IO` — never block the Compose UI thread.
10. Do NOT modify any files outside the `desktop/` directory.
11. Test on both Windows and macOS after each phase if possible.

## Phase Completion Checklist

After each phase:
1. All new desktop tests pass: `./gradlew :desktop:test`
2. All L1R2 tests still pass: `./gradlew :app:test`
3. App launches and the phase's feature works visually
4. Commit and push:
   ```bash
   git add desktop/
   git commit -m "L1.1R1 Phase N complete - [description]"
   git push origin desktop-agent
   ```
5. Report: "Phase N complete — X/Y tests passing, [what works]"

## Reading Order

1. `README.md` — scope, reuse map, repository setup
2. `architecture/system-overview.md` — what you're building
3. `architecture/compose-desktop.md` — UI architecture, threading, lifecycle
4. `ui/main-window.md` + `ui/system-tray.md` — what the user sees
5. `modules/reflector-discovery.md` — how targets are discovered
6. `modules/local-history.md` — local storage
7. `integration/desktop-registration.md` — state transitions
8. This file (`build-guide-l11.md`) — build order
9. L1 R1 vault — inherited module specs
10. L1 R2 vault — three-state model, registration, operational hardening
