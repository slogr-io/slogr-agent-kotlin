---
status: locked
version: 2.0
depends-on:
  - architecture/compose-desktop
---

# Main Window

## Window Properties

| Property | Value |
|---|---|
| Title | "Slogr" |
| Default size | 600 x 500 px |
| Minimum size | 500 x 400 px |
| Resizable | Yes |
| Close behavior | Minimize to tray (ADR-055) |
| Initial state | Hidden (tray-only). User opens via tray. |

## Layout

The window has a **left sidebar** with two items and a **content area** on the right.

### Sidebar

- Width: ~140px
- Items: "Dashboard" (default, selected on open) and "Settings"
- Dark background matching theme surface color
- Selected item highlighted with primary color
- Slogr logo at the top of the sidebar or in the header bar

### Dashboard View (default)

```
+------------------------------------------+
|  Slogr                   [SLOGR LOGO]    |
+---------------+--------------------------+
|               |                          |
|  Dashboard    |     Gaming     VoIP      |
|               |      GREEN     GREEN     |
|  Settings     |    12ms 0.0% 18ms 0.0%  |
|               |                          |
|               |       Streaming          |
|               |         RED              |
|               |      18ms 2.1%          |
|               |                          |
|               |  Last test: 2 min ago    |
|               |  [Run Test Now]          |
|               |                          |
|               |  Recent History (24h)    |
|               |  [sparkline chart]       |
|               |  12:00  16:00  20:00     |
|               |                          |
+---------------+--------------------------+
```

Shows 3 traffic type cards with per-profile grade (green/yellow/red), RTT, and loss.
Below: last test time, Run Test Now button, 24h sparkline chart.

### Dashboard — Empty State (no servers configured)

```
+------------------------------------------+
|  Slogr                   [SLOGR LOGO]    |
+---------------+--------------------------+
|               |                          |
|  Dashboard    |                          |
|               |   No servers configured  |
|  Settings     |                          |
|               |   Add a TWAMP server to  |
|               |   start monitoring your  |
|               |   connection quality.    |
|               |                          |
|               |   [Go to Settings]       |
|               |                          |
+---------------+--------------------------+
```

Clicking "Go to Settings" switches to the Settings view.

### Settings View

```
+------------------------------------------+
|  Slogr                   [SLOGR LOGO]    |
+---------------+--------------------------+
|               |                          |
|  Dashboard    |  Traffic Types           |
|               |  Select up to 3:         |
|  Settings     |  [ ] Gaming              |
|               |  [ ] VoIP / Video Calls  |
|               |  [ ] Streaming           |
|               |  [ ] General Internet    |
|               |  [ ] Cloud / SaaS        |
|               |  [ ] Remote Desktop      |
|               |  [ ] IoT / Telemetry     |
|               |  [ ] Financial Trading   |
|               |                          |
|               |  Test Interval: [5 min]  |
|               |  [ ] Include traceroute  |
|               |  ------------------------|
|               |  Servers                 |
|               |  (no servers yet)        |
|               |  [+ Add Server]          |
|               |  ------------------------|
|               |  Application             |
|               |  [ ] Start on login      |
|               |  [ ] Show notifications  |
|               |  Data: %APPDATA%\Slogr   |
|               |  ------------------------|
|               |  About                   |
|               |  Slogr Desktop v1.1.0    |
|               |  [Run Diagnostics]       |
+---------------+--------------------------+
```

Single scrollable view. NOT tabs. Sections separated by dividers.

## Key Design Decisions

- No separate settings window. Everything inside the main window.
- Profile dropdown selector REMOVED. The 3 traffic type cards on Dashboard replace it.
- Location cards REMOVED from Dashboard. Server management is in Settings → Servers.
- All servers are user-added at runtime. No hardcoded servers.
- The "Add Server" form is inline (IP, port, optional label).
- Each server has a remove button. Slogr auto-discovery servers (future) will not be removable.

## Files

| File | Action |
|------|--------|
| `desktop/ui/window/MainWindow.kt` | REWRITE — sidebar + 2 views |
| `desktop/ui/window/DashboardView.kt` | NEW — traffic cards + history |
| `desktop/ui/window/SettingsView.kt` | NEW — single scrollable settings |
| `desktop/ui/window/TrafficTypeCard.kt` | NEW — per-profile grade card |
| `desktop/ui/window/HistoryChart.kt` | KEEP — sparkline chart |
| `desktop/ui/window/GradeBadge.kt` | REMOVED — replaced by traffic cards |
| `desktop/ui/window/LocationCard.kt` | REMOVED |
| `desktop/ui/settings/SettingsWindow.kt` | REMOVED — settings are inline now |
