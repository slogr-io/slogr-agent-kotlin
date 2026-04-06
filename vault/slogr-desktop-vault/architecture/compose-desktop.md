---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
  - slogr-agent-vault/architecture/concurrency-model
---

# Compose Desktop Architecture

## Technology

- **Framework:** Compose Multiplatform for Desktop (JetBrains)
- **Language:** Kotlin
- **UI toolkit:** Compose UI (declarative, reactive)
- **System tray:** `androidx.compose.ui.window.Tray` composable
- **Window management:** `androidx.compose.ui.window.Window` composable
- **Packaging:** `jpackage` (JDK 21) or Conveyor (hydraulic.dev)

## Gradle Module Structure

```
slogr-desktop/
├── contracts/        ← SYMLINK or dependency on L1 contracts module
├── engine/           ← SYMLINK or dependency on L1 engine module
├── platform/         ← SYMLINK or dependency on L1 platform module (RabbitMQ, Pub/Sub, OTLP, WAL, health)
├── desktop-core/     ← NEW: desktop-specific logic
│   ├── history/      ← SQLite local history
│   ├── reflectors/   ← reflector discovery client
│   ├── profiles/     ← profile manager + freemium gating
│   ├── oauth/        ← desktop OAuth flow
│   └── scheduler/    ← desktop measurement scheduler (UI-driven)
├── desktop-ui/       ← NEW: Compose Desktop UI
│   ├── window/       ← main window composables
│   ├── tray/         ← system tray composables
│   ├── settings/     ← settings screen composables
│   ├── theme/        ← colors, typography, design tokens
│   └── state/        ← UI state management (ViewModel pattern)
└── app/              ← NEW: main entry point, wires everything
    └── Main.kt
```

The `contracts/`, `engine/`, and `platform/` modules are consumed as Gradle project dependencies from the L1 repo. The desktop app lives in the same monorepo as a separate Gradle subproject, or as a separate repo with the L1 modules published as Maven artifacts.

## Threading Model

```
Main thread (Compose UI thread)
├── Compose rendering loop
├── UI state updates (StateFlow → recomposition)
│
├── CoroutineScope("slogr-desktop")
│   ├── MeasurementScheduler coroutine
│   │   └── Per-reflector measurement coroutines (bounded by semaphore)
│   │       ├── TWAMP session (Dispatchers.IO, DatagramSocket)
│   │       ├── Traceroute (Dispatchers.IO, ProcessBuilder)
│   │       ├── ASN lookup (Dispatchers.Default, MMDB)
│   │       ├── Path change detection (Dispatchers.Default)
│   │       └── SLA evaluation (Dispatchers.Default)
│   ├── LocalHistoryWriter coroutine (SQLite writes, Dispatchers.IO)
│   ├── ReflectorDiscovery coroutine (HTTP call, Dispatchers.IO)
│   ├── RabbitMQ publisher coroutine (CONNECTED only)
│   ├── Pub/Sub poller coroutine (CONNECTED only)
│   ├── OTLP exporter coroutine (REGISTERED + CONNECTED)
│   ├── Health reporter coroutine (CONNECTED only)
│   ├── HistoryPruner coroutine (hourly, deletes entries > 24h)
│   └── OAuthCallbackServer coroutine (ephemeral, during sign-in only)
```

All measurement work runs on `Dispatchers.IO` to avoid blocking the Compose UI thread. Results flow back to the UI via `StateFlow` or `SharedFlow`.

## State Management

```kotlin
class DesktopAgentViewModel {
    // Agent state
    val agentState: StateFlow<AgentState>           // ANONYMOUS, REGISTERED, CONNECTED
    val apiKey: StateFlow<String?>                   // current key or null

    // Measurement state
    val currentResults: StateFlow<Map<ReflectorId, MeasurementResult>>
    val overallGrade: StateFlow<SlaGrade>            // worst grade across all reflectors
    val lastTestTime: StateFlow<Instant?>

    // Reflector state
    val reflectors: StateFlow<List<Reflector>>       // discovered reflectors
    val selectedReflectors: StateFlow<List<Reflector>> // user-selected subset

    // Profile state
    val selectedProfile: StateFlow<SlaProfile>       // user-selected profile
    val availableProfiles: StateFlow<List<SlaProfile>> // gated by plan

    // History
    val recentResults: StateFlow<List<HistoryEntry>> // last 24h from SQLite

    // UI state
    val isWindowVisible: StateFlow<Boolean>
    val isMeasuring: StateFlow<Boolean>
}
```

## Application Lifecycle

```
Install → First Launch:
  1. App starts, window visible
  2. Generate persistent fingerprint (ADR-034), write to disk
  3. Call GET /v1/reflectors, cache response
  4. Select nearest 3 free-tier reflectors
  5. Start measuring immediately (ANONYMOUS mode)
  6. Show results in window, update tray icon

Subsequent Launch (auto-start on login):
  1. App starts, window hidden (tray-only)
  2. Read fingerprint from disk
  3. Read cached reflectors (refresh if > 24h old)
  4. Read settings (selected profile, selected reflectors, API key)
  5. Determine state from API key
  6. Start measuring
  7. Tray icon shows current grade

User Opens Window (double-click tray icon):
  1. Window becomes visible
  2. Current results displayed
  3. 24h history from SQLite

User Closes Window (X button):
  1. Window hidden (minimize to tray)
  2. Measurement continues in background

User Clicks "Quit" (tray menu → Quit):
  1. Stop measurement scheduler
  2. If CONNECTED: flush OTLP batch, close RabbitMQ, close Pub/Sub
  3. Close SQLite connection
  4. Exit process
```

## Shutdown Sequence

Mirrors the server agent shutdown but simpler (no WAL drain needed for ANONYMOUS/REGISTERED):

```
1. Stop measurement scheduler (no new sessions)
2. Wait up to 10 seconds for in-flight TWAMP sessions to complete
3. Cancel remaining sessions after 10s
4. If CONNECTED:
   a. Flush OTLP exporter
   b. Publish final health signal
   c. Drain WAL (up to 5s)
   d. Close RabbitMQ connection
   e. Close Pub/Sub subscription
5. Close SQLite connection
6. Exit
```

## Error Handling

- TWAMP session failure: log, mark reflector as unreachable, try next reflector. Don't crash.
- Reflector discovery failure (network down): use cached list. If no cache, show "No reflectors available" in UI.
- SQLite write failure: log warning, continue measuring. Results still shown in UI from memory.
- RabbitMQ connection failure (CONNECTED): results go to WAL and SQLite. Reconnect with backoff.
- OAuth callback failure: show error message in UI, offer retry.
