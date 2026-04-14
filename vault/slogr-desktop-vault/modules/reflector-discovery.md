---
status: locked
version: 3.0
---

# Server Configuration (Runtime)

## Overview

The desktop app ships with zero built-in TWAMP servers. All servers are user-added at runtime via Settings > Servers > "Add Server."

## Active Server

Only ONE server is tested at a time. Users add multiple servers to a list and select which one is active via a dropdown. Measurement runs only against the active server.

When the first server is added, it becomes active automatically.

## Adding a Server

"Add Server" button shows inline form:
- IP address or hostname (required)
- Port (default 862)
- Label (optional, e.g., "GCP us-central1" or "My Office Router")

Servers persist in `settings.json` → `servers[]` array.

## Server Status

Each server in the list shows a status dot:
- **Green:** Last measurement to this server succeeded
- **Red:** Last measurement failed
- **Grey:** Never tested (just added or not active)

## ASN / ISP Resolution

ASN resolution for ISP display uses the ipinfo.io API (fallback path). Public IP is detected via ipify/ifconfig.me/checkip.amazonaws.com, then `GET https://ipinfo.io/{ip}/org` returns the ASN and ISP name. Results are cached for 24 hours.

When `Ip2AsnResolver` is available in the engine module (expected in v1.0.8+), the desktop app will switch to the bundled ip2asn database for instant, offline ASN lookups — no API call needed.

## Future: Auto-Discovery

When Slogr deploys public TWAMP reflectors, `GET /v1/reflectors` will auto-populate the server list. When Enterprise/SaaS connectivity is wired (v1.2.0), the bootstrap endpoint will return the reflector list.

## Future: Enterprise Deployment

IT admin pushes servers via MSI property or env var:
```
msiexec /i Slogr.msi /qn SLOGR_API_KEY=sk_live_... SLOGR_SERVER=https://slogr.enterprise.com
```
The bootstrap endpoint returns the server list — no manual server entry needed.
