---
status: locked
version: 3.0
---

# Main Window

## Window Properties

| Property | Value |
|---|---|
| Title | "Slogr" |
| Icon | Slogr "g" logo with teal orbital dots (slogr.ico) |
| Default size | 620 x 520 px |
| Minimum size | 500 x 400 px |
| Resizable | Yes |
| Close behavior | Minimize to tray |
| Initial state | Hidden (tray-only). User opens via tray. |
| Theme | Light (white background, dark text, green accent) |

## Layout

Left sidebar (140px) + content area. Two views: Dashboard (default) and Settings.

### Sidebar

- Slogr "g" logo at top (slogr-logo.png — green 'g' with teal orbital dots)
- "Dashboard" and "Settings" navigation items
- Selected item: dark green text on light green background
- "Quit" at bottom
- Light grey background (#F5F5F5)

### Dashboard View

**ISP Display Row** — "Connected via: ISP_NAME (ASXXXX)" above traffic cards. Detected via ipify + ipinfo.io API, cached 24h. Hidden if detection fails.

**Update Banner** — Appears at top of window when a newer version is available. Shows "Slogr vX.Y.Z is available." with "Update Now" (opens browser) and "Later" (dismisses for 24h) buttons. Required updates show a non-dismissable AlertDialog.

Shows 3 traffic type cards in a row. Each card:
- Traffic type icon (emoji)
- Type name
- Grade dot (green/yellow/red/grey)
- RTT in ms
- Loss percentage
- Grey dot + "Testing..." when measurement in progress

Cards start grey when cycle begins, light up progressively as each per-type TWAMP session completes (~3 seconds between each).

Below cards: "Last test: X min ago" + "Run Test Now" button.
Below that: "Recent History (24h)" sparkline chart.

Empty state (no servers): "No servers configured" + "Go to Settings" button.

### Settings View (Accordion)

4 collapsible sections, all collapsed by default:

**Traffic Types** — selected 3 types shown, "Show all types (N more)" expander, test interval dropdown, traceroute toggle with disclaimer.

**Servers** — active server dropdown, server list with status dots and remove buttons, "Add Server" form (IP, port, label).

**Application** — start on login, show notifications checkboxes, data directory path.

**About** — version, slogr.io link, "Run Diagnostics" button, "Share Results" button (exports zip of last 20 tests + diagnostics + system info).
