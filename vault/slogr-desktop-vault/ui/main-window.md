---
status: locked
version: 1.0
depends-on:
  - architecture/compose-desktop
---

# Main Window

## Window Properties

| Property | Value |
|---|---|
| Title | "Slogr" |
| Default size | 480 × 640 px |
| Minimum size | 400 × 500 px |
| Resizable | Yes |
| Close behavior | Minimize to tray (ADR-055) |
| Initial state | Visible on first launch. Hidden (tray-only) on auto-start. |

## Layout

```
┌──────────────────────────────────────────┐
│  Slogr                    [─] [□] [X]    │  ← title bar (OS native)
├──────────────────────────────────────────┤
│                                          │
│  ● Connection Quality                    │  ← overall grade badge
│  ■■■ GREEN                               │     (green/yellow/red, large)
│                                          │
│  Profile: [Internet      ▼]             │  ← profile selector dropdown
│                                          │
├──────────────────────────────────────────┤
│  Locations                               │
│                                          │
│  🟢 US East      18ms   0.0% loss       │  ← per-reflector results
│  🟢 EU West      42ms   0.0% loss       │
│  🟡 AP Southeast 89ms   0.5% loss       │
│                                          │
│  Last test: 2 minutes ago                │
│  [▶ Run Test Now]                        │  ← manual test button
│                                          │
├──────────────────────────────────────────┤
│  Recent History (24h)                    │
│                                          │
│  ▂▃▂▂▅▂▂▂▂▂▇▂▂▂▂▂▂▂▃▂▂▂▂▂             │  ← sparkline or mini chart
│  12:00  14:00  16:00  18:00  20:00       │     showing RTT over time
│                                          │
├──────────────────────────────────────────┤
│  [Sign In]              [⚙ Settings]    │  ← footer (ANONYMOUS)
│  -- or --                                │
│  nasim@slogr.io (Free)  [⚙ Settings]    │  ← footer (REGISTERED)
│  -- or --                                │
│  nasim@slogr.io (Pro)   [Dashboard]      │  ← footer (CONNECTED)
│  [⚙ Settings]                            │
└──────────────────────────────────────────┘
```

## Sections

### 1. Grade Badge (Top)

Large, prominent indicator of overall connection quality.

- **GREEN:** All reflectors meet profile thresholds. Text: "Connection Quality: GREEN"
- **YELLOW:** One or more reflectors in YELLOW range. Text: "Connection Quality: YELLOW — elevated latency/jitter detected"
- **RED:** One or more reflectors in RED range. Text: "Connection Quality: RED — significant degradation detected"
- **GREY:** No measurements yet (first launch, measuring...). Text: "Measuring..."

The overall grade is the **worst** grade across all active reflectors.

### 2. Profile Selector

Dropdown with available profiles. Free users see Internet + their chosen additional profile + locked options with upgrade prompts. Paid users see all profiles.

Changing the profile immediately recalculates all grades without re-measuring.

### 3. Location Cards

One card per active reflector. Each shows:
- Grade indicator (colored dot)
- Region name (human-readable, e.g., "US East" not "us-east-1")
- Average RTT in milliseconds
- Loss percentage
- Clicking a card could expand to show jitter, min/max RTT, and traceroute (future enhancement)

### 4. Run Test Now Button

Triggers an immediate measurement cycle against all active reflectors. Button shows progress indicator while tests run. Disabled while a test is in progress.

### 5. Recent History

A sparkline or mini bar chart showing RTT over the last 24 hours, sourced from the local SQLite history. Gives the user a sense of whether their connection quality has been stable or volatile.

For ANONYMOUS/REGISTERED users, this is the only history available.

For CONNECTED users, a "View Full History" link opens the SaaS dashboard in the browser.

### 6. Footer

State-dependent:
- **ANONYMOUS:** "Sign In" button + Settings gear icon
- **REGISTERED:** User email + plan badge ("Free") + Settings gear icon
- **CONNECTED:** User email + plan badge ("Pro") + "Open Dashboard" button (opens browser) + Settings gear icon

## "Open Dashboard" Button (CONNECTED Only)

Opens `https://app.slogr.io/dashboard` in the system browser. The URL includes a pre-auth token so the user doesn't have to sign in again:

```
https://app.slogr.io/dashboard?token=<short-lived-jwt>
```

The BFF validates the token and establishes a browser session.

## Upgrade Prompts

Appear contextually, not intrusively:
- Locked profile: "Unlock Gaming profile — Upgrade to Pro"
- Locked reflector: "3 of 8 locations available — Upgrade for all"
- History section (ANONYMOUS): "Sign in for free to unlock OTLP export"
- History section (REGISTERED): "Upgrade to Pro for unlimited history and root cause analysis"

## Files

| File | Action |
|------|--------|
| `desktop-ui/window/MainWindow.kt` | NEW — top-level window composable |
| `desktop-ui/window/GradeBadge.kt` | NEW — large grade indicator |
| `desktop-ui/window/ProfileSelector.kt` | NEW — dropdown with freemium gating |
| `desktop-ui/window/LocationCard.kt` | NEW — per-reflector result card |
| `desktop-ui/window/HistoryChart.kt` | NEW — sparkline from SQLite data |
| `desktop-ui/window/FooterBar.kt` | NEW — state-dependent footer |
