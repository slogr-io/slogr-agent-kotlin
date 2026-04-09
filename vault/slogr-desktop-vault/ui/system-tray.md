---
status: locked
version: 3.0
---

# System Tray

## Implementation

Tray icon: AWT `TrayIcon` (Compose Desktop has no native tray API).
Tray menu: **Compose Desktop borderless Window** (not AWT PopupMenu — that produces overlapping text on Windows).

Right-click triggers a small Compose window near the cursor. Dismisses on focus loss.

## Tray Icon States

| State | Color | Condition |
|---|---|---|
| All types GREEN | Green (#4CAF50) | All 3 active traffic types pass SLA |
| Any type YELLOW | Yellow (#FFC107) | At least one type in YELLOW |
| Any type RED | Red (#F44336) | At least one type in RED |
| No data / stale | Grey (#9E9E9E) | No successful measurement recently |
| No servers | Dark (#212121) | No servers configured |

## Tray Menu — Normal (servers configured)

```
Status: GREEN
Last test: 2 min ago
─────────────────────
Run Test Now
Open Slogr
─────────────────────
Quit
```

5 items. "Run Test Now" disabled while testing. Light theme (white bg, dark text).

## Tray Menu — No Servers

```
No servers configured
Add a server to start
─────────────────────
Open Slogr
─────────────────────
Quit
```

3 items (no "Run Test Now" — nothing to test).

## Launch Behavior

App starts in tray-only mode always. No `--background` flag. Window opens only on user action (double-click tray or "Open Slogr").

## Desktop Notifications

Grade-change notifications via AWT SystemTray. Respects "Show notifications" setting.
