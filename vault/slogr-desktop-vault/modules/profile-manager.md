---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
  - slogr-agent-vault/modules/traceroute-asn-pathchange-sla
---

# Profile Manager

## Overview

The desktop user selects which SLA profile to evaluate against. The server agent receives its profile via `set_schedule` command or config file. The desktop app puts this choice in the user's hands via the UI.

## Available Profiles

From the R1 vault, the agent supports 27+ SLA profiles. The desktop app exposes a simplified subset:

| Profile | Display Name | Free Tier | Thresholds (GREEN) |
|---|---|---|---|
| `internet` | Internet | ✅ Always available | RTT < 100ms, Jitter < 30ms, Loss < 1% |
| `gaming` | Gaming | ✅ Free pick (1 of 3) | RTT < 50ms, Jitter < 15ms, Loss < 0.5% |
| `voip` | VoIP / Video | ✅ Free pick (1 of 3) | RTT < 150ms, Jitter < 20ms, Loss < 1% |
| `streaming` | Streaming | ✅ Free pick (1 of 3) | RTT < 200ms, Jitter < 50ms, Loss < 2% |
| `custom` | Custom | ❌ Paid only | User-defined thresholds |

## Freemium Gating

### Free users (ANONYMOUS or REGISTERED with `sk_free_*`)

- `internet` profile: always available
- One additional profile of their choice (Gaming, VoIP, or Streaming)
- Custom profiles: locked with "Upgrade to Pro" prompt

### Paid users (CONNECTED with `sk_live_*`)

- All profiles available
- Custom profile: user sets their own RTT/jitter/loss thresholds

## Profile Selection in UI

The profile selector appears in:
1. **Main window** — prominent dropdown or segmented control at the top
2. **Tray context menu** — submenu under "Monitoring Profile"
3. **Settings** — full profile configuration page

When the user changes profile, the current grade immediately recalculates against the new thresholds. No re-measurement needed — the last measurement result is re-evaluated.

## Active Profile Storage

```kotlin
// Stored in settings file:
// %APPDATA%\Slogr\settings.json (Windows)
// ~/Library/Application Support/Slogr/settings.json (macOS)

data class DesktopSettings(
    val activeProfile: String = "internet",         // profile name
    val secondFreeProfile: String? = null,          // user's chosen free pick
    val customThresholds: CustomThresholds? = null, // paid only
    val selectedReflectorIds: List<String> = emptyList(),
    val testIntervalSeconds: Int = 300,
    val autoStartEnabled: Boolean = true,
    val apiKey: String? = null                       // stored encrypted
)
```

## Profile Interaction with Measurement

The profile does NOT change what gets measured. TWAMP sessions produce the same RTT/jitter/loss numbers regardless of profile. The profile only determines the **grade** — GREEN, YELLOW, or RED — by comparing the measurement against the profile's thresholds.

```kotlin
// In the measurement pipeline:
val result = twampController.runSession(reflector)      // same always
val grade = slaEvaluator.evaluate(result, activeProfile) // profile determines grade
localHistory.insert(result, reflector, activeProfile)
uiState.update(reflector.id, result, grade)
trayIcon.updateColor(worstGrade(allCurrentGrades))
```

## CONNECTED Mode: Profile vs Schedule

When a desktop agent is CONNECTED and receives a `set_schedule` command from the SaaS, the command may include a profile for each target. In this case:

- Server-pushed profiles take priority for server-pushed targets
- The user's locally selected profile applies to locally configured reflectors and custom targets
- The UI shows which profile is active per target and whether it was set by the user or by the SaaS admin

## Files

| File | Action |
|------|--------|
| `desktop-core/profiles/ProfileManager.kt` | NEW — profile selection, freemium gating |
| `desktop-core/profiles/FreemiumGate.kt` | NEW — enforces free/paid limits |
| `desktop-core/profiles/DesktopSettings.kt` | NEW — settings persistence |
