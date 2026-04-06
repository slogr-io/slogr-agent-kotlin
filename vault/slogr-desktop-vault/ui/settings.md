---
status: locked
version: 1.0
depends-on:
  - ui/main-window
  - modules/profile-manager
---

# Settings Screen

## Access

Opened from tray menu → "Settings..." or main window footer → gear icon. Opens as a separate window or a full-screen view within the main window.

## Sections

### 1. Account

```
┌─────────────────────────────────────────┐
│  Account                                │
│                                         │
│  [ANONYMOUS]                            │
│  Not signed in.                         │
│  [Sign In with LinkedIn]                │
│  [Sign In with Google]                  │
│                                         │
│  -- or (REGISTERED) --                  │
│                                         │
│  nasim@example.com                      │
│  Plan: Free                             │
│  [Upgrade to Pro]                       │
│  [Sign Out]                             │
│                                         │
│  -- or (CONNECTED) --                   │
│                                         │
│  nasim@example.com                      │
│  Plan: Pro                              │
│  Agent ID: 550e8400-...                 │
│  [Open Dashboard]                       │
│  [Enter API Key]    [Sign Out]          │
│                                         │
└─────────────────────────────────────────┘
```

- **Enter API Key:** Manual key entry field for enterprise deployment or when auto-detection doesn't work.
- **Sign Out:** Transitions to ANONYMOUS (see `desktop-registration.md`).

### 2. Monitoring

```
┌─────────────────────────────────────────┐
│  Monitoring                             │
│                                         │
│  Profile: [Internet          ▼]         │
│                                         │
│  Test Interval: [5 minutes   ▼]         │
│    Options: 1 min, 2 min, 5 min,        │
│    10 min, 15 min, 30 min               │
│                                         │
│  Traceroute: [✓] Include with tests     │
│                                         │
└─────────────────────────────────────────┘
```

- **Test interval:** How often the app runs measurements. Default 5 minutes. Shorter intervals use more resources.
- **Traceroute toggle:** Enabled by default. Some users may want to disable if traceroute causes issues.

### 3. Locations

```
┌─────────────────────────────────────────┐
│  Locations                              │
│                                         │
│  Slogr Reflectors (auto-selected)       │
│  ☑ US East (nearest, 18ms)             │
│  ☑ EU West (42ms)                      │
│  ☑ AP Southeast (89ms)                 │
│  ☐ US West (locked — Pro)              │
│  ☐ Middle East (locked — Pro)          │
│                                         │
│  Custom Targets (Pro only)              │
│  10.0.1.5:862 — "Office router"        │
│  [+ Add custom target]                  │
│                                         │
│  [Refresh reflector list]               │
│                                         │
└─────────────────────────────────────────┘
```

- Free users can check up to 3 free-tier reflectors. Paid reflectors show "(locked — Pro)".
- Custom targets show an "Add custom target" dialog: IP/hostname, port (default 862), label, profile override.
- "Refresh reflector list" calls `GET /v1/reflectors` and updates the cache.

### 4. Application

```
┌─────────────────────────────────────────┐
│  Application                            │
│                                         │
│  [✓] Start Slogr on login              │
│  [✓] Show desktop notifications        │
│  [✓] Minimize to tray on close         │
│                                         │
│  Data directory: %APPDATA%\Slogr       │
│  History database: 1.2 MB (24h)         │
│  [Clear history]                        │
│                                         │
│  ASN Database                           │
│  Status: Loaded (2026-03-15, 7.2 MB)   │
│  [Update ASN Database]                  │
│                                         │
└─────────────────────────────────────────┘
```

- **Start on login:** Toggle for auto-start (ADR-056). Modifies Windows registry / macOS LaunchAgent.
- **Show desktop notifications:** Toggle for grade-change notifications.
- **Minimize to tray on close:** Always on (ADR-055), but shown here for transparency. If user disables, close button quits the app.
- **Clear history:** Deletes all SQLite entries. Confirmation dialog required.
- **Update ASN Database:** Downloads latest MaxMind GeoLite2-ASN MMDB. Shows progress. Requires MaxMind license key (prompted on first use, stored encrypted).

### 5. About

```
┌─────────────────────────────────────────┐
│  About                                  │
│                                         │
│  Slogr Desktop v1.1.0                   │
│  © 2026 Slogr                           │
│                                         │
│  Website: slogr.io                      │
│  Support: support@slogr.io              │
│                                         │
│  [Check for Updates]                    │
│  [Run Diagnostics]                      │
│                                         │
└─────────────────────────────────────────┘
```

- **Check for Updates:** Calls a version check API and prompts if a newer version is available.
- **Run Diagnostics:** Desktop equivalent of `slogr-agent doctor` (ADR-038). Checks network connectivity, reflector reachability, TWAMP handshake, DNS, TLS, API key validity. Shows results in-app.

## Settings Persistence

All settings stored in `settings.json` in the app data directory:

```json
{
  "activeProfile": "internet",
  "secondFreeProfile": "gaming",
  "testIntervalSeconds": 300,
  "tracerouteEnabled": true,
  "selectedReflectorIds": ["550e8400-...", "660e8400-...", "770e8400-..."],
  "customTargets": [
    {
      "host": "10.0.1.5",
      "port": 862,
      "label": "Office router",
      "profile": "voip"
    }
  ],
  "autoStartEnabled": true,
  "notificationsEnabled": true,
  "minimizeToTrayOnClose": true,
  "apiKey": "<encrypted>",
  "maxmindLicenseKey": "<encrypted>"
}
```

Encrypted fields use OS-level encryption (DPAPI on Windows, Keychain on macOS). Non-sensitive fields stored in plaintext.

## Files

| File | Action |
|------|--------|
| `desktop-ui/settings/SettingsWindow.kt` | NEW — settings window composable |
| `desktop-ui/settings/AccountSection.kt` | NEW — sign-in/sign-out/upgrade |
| `desktop-ui/settings/MonitoringSection.kt` | NEW — profile, interval, traceroute toggle |
| `desktop-ui/settings/LocationsSection.kt` | NEW — reflector selection, custom targets |
| `desktop-ui/settings/ApplicationSection.kt` | NEW — auto-start, notifications, data |
| `desktop-ui/settings/AboutSection.kt` | NEW — version, diagnostics |
| `desktop-ui/settings/AddTargetDialog.kt` | NEW — custom target entry dialog |
