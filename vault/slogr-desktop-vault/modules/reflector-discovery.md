---
status: locked
version: 2.0
depends-on:
  - architecture/system-overview
---

# Server Configuration (Runtime)

## Overview

The desktop app ships with **zero built-in TWAMP servers**. All servers are added by the user at runtime via the Settings view inside the main window: Settings → Servers → "Add Server."

Servers persist in `settings.json` and survive restarts. When at least one server is configured, the app begins measuring on the next cycle.

## Future: Reflector Discovery API

When Slogr deploys public TWAMP reflectors, a future update will add auto-discovery via:

```
GET https://api.slogr.io/v1/reflectors
```

This endpoint does not exist yet. Until then, all servers are user-configured.

## Adding a Server

The user clicks "Add Server" in Settings → Servers and fills in:

| Field | Required | Default | Example |
|-------|----------|---------|---------|
| IP address or hostname | Yes | — | `34.123.45.67` or `reflector.example.com` |
| Port | No | `862` | `862` |
| Label | No | `{host}:{port}` | `GCP us-central1` or `My Office Router` |

Servers are stored in `settings.json`:

```json
{
  "servers": [
    {
      "id": "auto-generated-uuid",
      "host": "34.123.45.67",
      "port": 862,
      "label": "GCP us-central1"
    },
    {
      "id": "auto-generated-uuid",
      "host": "10.0.1.5",
      "port": 862,
      "label": "My Office Router"
    }
  ]
}
```

## Removing a Server

Each server in the list has a remove button (x). Clicking it removes the server from `settings.json` and stops measurement against it on the next cycle.

## Empty State

On first launch with no servers:
- Tray icon: **black** (cannot test)
- Dashboard: shows "No servers configured" with a "Go to Settings" button
- No measurement runs until at least one server is added

## Server Status

Each server in the Settings list shows a status dot:
- **Green:** Last measurement succeeded
- **Red:** Last measurement failed (unreachable, connection refused, timeout)
- **Grey:** Never tested yet (just added)

## Files

| File | Action |
|------|--------|
| `desktop-core/settings/DesktopSettings.kt` | UPDATED — `servers` list replaces `selectedReflectorIds` + `customTargets` |
| `desktop-core/settings/DesktopSettingsStore.kt` | UPDATED — reads/writes server list |
