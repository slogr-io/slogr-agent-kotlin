---
status: locked
version: 1.0
depends-on:
  - architecture/compose-desktop
---

# System Tray

## Tray Icon

The tray icon reflects the current overall grade (worst across all active reflectors):

| State | Icon Color | Tooltip |
|---|---|---|
| GREEN | Green circle/dot | "Slogr — Connection quality: GREEN" |
| YELLOW | Yellow circle/dot | "Slogr — Connection quality: YELLOW" |
| RED | Red circle/dot | "Slogr — Connection quality: RED" |
| Measuring | Grey circle/dot | "Slogr — Measuring..." |
| Error | Grey circle with X | "Slogr — No reflectors available" |

Icon updates after each measurement cycle. On Windows, the icon sits in the system tray (notification area). On macOS, it sits in the menu bar.

## Compose Desktop Tray Implementation

```kotlin
@Composable
fun ApplicationScope.SlogrTray(
    grade: SlaGrade,
    onOpenWindow: () -> Unit,
    onRunTest: () -> Unit,
    onQuit: () -> Unit
) {
    val icon = when (grade) {
        SlaGrade.GREEN -> painterResource("tray-green.png")
        SlaGrade.YELLOW -> painterResource("tray-yellow.png")
        SlaGrade.RED -> painterResource("tray-red.png")
        else -> painterResource("tray-grey.png")
    }

    Tray(
        icon = icon,
        tooltip = "Slogr — Connection quality: ${grade.name}",
        onAction = onOpenWindow,    // double-click opens window
        menu = {
            // Menu items defined below
        }
    )
}
```

## Tray Context Menu (Right-Click)

```
┌───────────────────────────────┐
│  Connection: GREEN            │  ← status (non-clickable label)
│  Last test: 2 min ago         │  ← timestamp (non-clickable label)
│─────────────────────────────│
│  ▶ Monitoring Profile         │  ← submenu
│    ● Internet                 │     radio buttons
│    ○ Gaming                   │
│    ○ VoIP / Video             │
│    ○ Streaming                │
│    ○ Custom... (Pro)          │     greyed if free
│─────────────────────────────│
│  Run Test Now                 │  ← triggers immediate test
│  Open Window                  │  ← shows/focuses main window
│  Open Dashboard               │  ← opens browser (CONNECTED only, hidden otherwise)
│─────────────────────────────│
│  Settings...                  │  ← opens settings window
│─────────────────────────────│
│  Quit                         │  ← exits the application
└───────────────────────────────┘
```

### Menu Behavior

- **Monitoring Profile submenu:** Radio buttons. Selecting a profile immediately recalculates grades. Free users see locked items with "(Pro)" suffix — clicking shows upgrade prompt.
- **Run Test Now:** Triggers measurement against all active reflectors. Menu item text changes to "Testing..." while in progress.
- **Open Window:** Brings the main window to focus. If window was minimized to tray, it becomes visible.
- **Open Dashboard:** Only shown when agent state is CONNECTED. Opens `app.slogr.io/dashboard` in browser.
- **Settings:** Opens the settings window (or brings it to focus if already open).
- **Quit:** Runs the shutdown sequence (see `compose-desktop.md`), then exits.

## Desktop Notifications

The app sends OS notifications when the grade changes:

| Transition | Notification |
|---|---|
| GREEN → YELLOW | "Connection quality degraded — elevated latency detected" |
| GREEN → RED | "Connection quality poor — significant degradation" |
| YELLOW → RED | "Connection quality worsened" |
| RED → GREEN | "Connection quality restored" |
| YELLOW → GREEN | "Connection quality improved" |

Notifications are sent via:
- **Windows:** `java.awt.SystemTray.displayMessage()` or Windows toast notifications via JNA
- **macOS:** `NSUserNotificationCenter` via JNA or `ProcessBuilder("osascript", "-e", "display notification...")`

Notifications are enabled by default. User can disable in Settings.

## macOS Menu Bar Conventions

On macOS, the tray icon sits in the menu bar (right side, near the clock). macOS conventions:
- Single click on menu bar icon shows the dropdown menu (same as right-click on Windows)
- The menu bar icon should be a template image (monochrome) that adapts to light/dark mode
- Use `NSImage.setTemplate(true)` equivalent in Compose Desktop

## Files

| File | Action |
|------|--------|
| `desktop-ui/tray/SlogrTray.kt` | NEW — Compose `Tray` composable |
| `desktop-ui/tray/TrayMenuBuilder.kt` | NEW — context menu construction |
| `desktop-ui/tray/DesktopNotifier.kt` | NEW — OS notification bridge |
| `resources/tray-green.png` | NEW — 16x16 and 32x32 green icon |
| `resources/tray-yellow.png` | NEW — 16x16 and 32x32 yellow icon |
| `resources/tray-red.png` | NEW — 16x16 and 32x32 red icon |
| `resources/tray-grey.png` | NEW — 16x16 and 32x32 grey icon |
| `resources/tray-template.png` | NEW — macOS menu bar template image |
