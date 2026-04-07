---
status: locked
version: 2.0
depends-on:
  - architecture/compose-desktop
---

# System Tray

## Tray Icon States

| State | Icon Color | Condition |
|---|---|---|
| All profiles GREEN | Green circle | All 3 monitored traffic types pass SLA |
| Any profile YELLOW | Yellow circle | At least one traffic type in YELLOW range |
| Any profile RED | Red circle | At least one traffic type in RED range |
| Results stale | Grey-tinted circle | No test completed in 2x configured interval |
| Cannot test | Black circle | No servers configured, or all servers unreachable |

Icon updates after each measurement cycle.

## Launch Behavior

On startup (including auto-start), the app starts in **tray-only mode**. The window is NOT shown. The tray icon appears. If servers are configured, measurement begins immediately. If no servers are configured, the tray icon is black.

The user opens the window by:
- Double-clicking the tray icon
- Right-click → "Open Slogr"

There is no `--background` flag. Tray-only IS the default, always.

## Tray Context Menu — Normal (servers configured)

Built with raw `java.awt.PopupMenu` (not Compose menu API — fixes text overlap on Windows).

```
+-------------------------------+
|  GREEN                        |  non-clickable grade label
|  Last test: 2 min ago         |  non-clickable timestamp
|-------------------------------|
|  Run Test Now                 |  triggers immediate measurement
|  Open Slogr                  |  opens/focuses main window
|-------------------------------|
|  Quit                         |  exits the application
+-------------------------------+
```

Exactly 5 items + 2 separators. Nothing else.

## Tray Context Menu — No Servers

```
+-------------------------------+
|  No servers configured        |  non-clickable
|  Add a server to start        |  non-clickable
|-------------------------------|
|  Open Slogr                  |  opens window so user can add servers
|-------------------------------|
|  Quit                         |
+-------------------------------+
```

"Run Test Now" is hidden — nothing to test against.

## Desktop Notifications

Grade-change notifications via AWT SystemTray:

| Transition | Message |
|---|---|
| GREEN to YELLOW | "Connection quality degraded" |
| GREEN to RED | "Connection quality poor" |
| YELLOW to RED | "Connection quality worsened" |
| RED to GREEN | "Connection quality restored" |
| YELLOW to GREEN | "Connection quality improved" |

Notifications respect the "Show notifications" setting toggle.

## Files

| File | Action |
|------|--------|
| `desktop/ui/tray/SlogrTray.kt` | REWRITE — AWT PopupMenu, 2 menu variants |
| `desktop/ui/tray/TrayIconGenerator.kt` | UPDATED — add grey-tint and black icons |
| `desktop/core/notifications/DesktopNotifier.kt` | KEEP |
