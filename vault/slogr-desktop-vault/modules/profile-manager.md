---
status: locked
version: 2.0
depends-on:
  - architecture/system-overview
---

# Profile Manager (Traffic Types)

## Overview

The desktop user selects **exactly 3 traffic types** to monitor. Each measurement cycle evaluates the same TWAMP result against all 3 selected SLA profiles simultaneously. The dashboard shows 3 icons with per-profile grades.

## Available Traffic Types (8 total)

| Name | Display Name | Free Tier | GREEN Thresholds |
|---|---|---|---|
| `internet` | General Internet | Yes | RTT < 100ms, Jitter < 30ms, Loss < 1% |
| `gaming` | Gaming | Yes | RTT < 50ms, Jitter < 15ms, Loss < 0.5% |
| `voip` | VoIP / Video Calls | Yes | RTT < 150ms, Jitter < 20ms, Loss < 1% |
| `streaming` | Streaming | Yes | RTT < 200ms, Jitter < 50ms, Loss < 2% |
| `cloud` | Cloud / SaaS | Paid | RTT < 100ms, Jitter < 25ms, Loss < 0.5% |
| `rdp` | Remote Desktop | Paid | RTT < 80ms, Jitter < 20ms, Loss < 0.5% |
| `iot` | IoT / Telemetry | Paid | RTT < 500ms, Jitter < 100ms, Loss < 5% |
| `trading` | Financial Trading | Paid | RTT < 10ms, Jitter < 2ms, Loss < 0.01% |

## Selection Rules

- User selects exactly 3 traffic types
- Default selection: Gaming, VoIP, Streaming
- When user checks a 4th, show "Uncheck one first" — do not auto-uncheck
- Selection stored in `settings.json` as `activeProfiles: ["gaming", "voip", "streaming"]`

## Freemium Gating

- **Free users (ANONYMOUS/REGISTERED):** 4 types available (Internet, Gaming, VoIP, Streaming). Paid types locked with upgrade prompt.
- **Paid users (CONNECTED):** All 8 types available.

## Profile Interaction with Measurement

The profile does NOT change what gets measured. One TWAMP session produces RTT/jitter/loss numbers. Those same numbers are evaluated against all 3 active profiles to produce 3 separate grades:

```kotlin
val result = engine.measure(target, profile = baseProfile)
val grade1 = SlaEvaluator.evaluate(result, profileForTrafficType1)
val grade2 = SlaEvaluator.evaluate(result, profileForTrafficType2)
val grade3 = SlaEvaluator.evaluate(result, profileForTrafficType3)
overallGrade = worstOf(grade1, grade2, grade3)
```

## Files

| File | Action |
|------|--------|
| `desktop/core/profiles/ProfileManager.kt` | REWRITE — 8 types, 3 active, enforce-3 logic |
| `desktop/core/profiles/DesktopProfile.kt` | NEW — traffic type metadata + SLA thresholds |
