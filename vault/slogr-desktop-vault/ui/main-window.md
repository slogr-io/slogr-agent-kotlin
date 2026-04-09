---
status: locked
version: 3.0
---

# Main Window

## Window Properties

| Property | Value |
|---|---|
| Title | "Slogr" |
| Icon | Slogr "S" favicon (slogr.ico) |
| Default size | 620 x 520 px |
| Minimum size | 500 x 400 px |
| Resizable | Yes |
| Close behavior | Minimize to tray |
| Initial state | Hidden (tray-only). User opens via tray. |
| Theme | Light (white background, dark text, green accent) |

## Layout

Left sidebar (140px) + content area. Two views: Dashboard (default) and Settings.

### Sidebar

- Slogr text logo at top (slogr_FINAL_516x268.png)
- "Dashboard" and "Settings" navigation items
- Selected item: dark green text on light green background
- "Quit" at bottom
- Light grey background (#F5F5F5)

### Dashboard View

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
