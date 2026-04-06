---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
---

# Reflector Discovery

## Overview

The desktop app discovers Slogr measurement reflectors via a public API endpoint. Reflectors are mesh agents (tenant `00001`) flagged as `is_public_reflector = true`.

## API Endpoint (L3 BFF — New)

```
GET https://api.slogr.io/v1/reflectors

No authentication required.
Rate limit: 10 requests/minute per source IP.
```

### Response

```json
{
  "reflectors": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "region": "us-east-1",
      "cloud": "aws",
      "host": "reflector-use1.slogr.io",
      "port": 862,
      "latitude": 39.0438,
      "longitude": -77.4874,
      "tier": "free"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440002",
      "region": "eu-west-1",
      "cloud": "aws",
      "host": "reflector-euw1.slogr.io",
      "port": 862,
      "latitude": 53.3498,
      "longitude": -6.2603,
      "tier": "free"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440003",
      "region": "ap-southeast-1",
      "cloud": "aws",
      "host": "reflector-apse1.slogr.io",
      "port": 862,
      "latitude": 1.3521,
      "longitude": 103.8198,
      "tier": "free"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440010",
      "region": "us-west-2",
      "cloud": "aws",
      "host": "reflector-usw2.slogr.io",
      "port": 862,
      "latitude": 45.5231,
      "longitude": -122.6765,
      "tier": "paid"
    }
  ],
  "your_region": "pk-sindh",
  "your_ip": "203.0.113.45"
}
```

The `your_region` and `your_ip` fields let the desktop app know the user's detected location without an extra API call. The BFF resolves the caller's IP via MaxMind GeoLite2-City server-side.

### Tier Field

| Tier | Accessible By |
|---|---|
| `"free"` | All users (ANONYMOUS, REGISTERED, CONNECTED) |
| `"paid"` | REGISTERED and CONNECTED only (`sk_free_*` or `sk_live_*` key present) |

Free users: app filters to `tier == "free"` reflectors only. Paid users: all reflectors.

## Nearest Selection Algorithm

On first launch or when the reflector list refreshes:

1. Parse all reflectors matching the user's tier
2. Compute haversine distance from `your_region` coordinates to each reflector
3. Sort by distance ascending
4. Select the nearest 3 (free) or all (paid)
5. Optionally, run a quick latency probe (single TWAMP packet) to each selected reflector and reorder by actual RTT

Step 5 is optional but recommended — geographic distance doesn't always correlate with network latency.

```kotlin
fun selectNearest(
    reflectors: List<Reflector>,
    userLat: Double,
    userLon: Double,
    maxCount: Int
): List<Reflector> {
    return reflectors
        .map { it to haversineKm(userLat, userLon, it.latitude, it.longitude) }
        .sortedBy { it.second }
        .take(maxCount)
        .map { it.first }
}
```

## Caching

- **Cache location:** `%APPDATA%\Slogr\reflectors.json` (Windows) or `~/Library/Application Support/Slogr/reflectors.json` (macOS)
- **Cache TTL:** 24 hours
- **On cache miss or expiry:** call `GET /v1/reflectors`
- **On network error with valid cache:** use cached list, log warning
- **On network error with no cache (first launch, offline):** show error in UI: "Unable to discover measurement endpoints. Check your internet connection."

## Adding Custom Targets (Paid Only)

CONNECTED users can add custom TWAMP targets in Settings → Locations → "Add custom target":

```
IP/hostname: 10.0.1.5
Port: 862
Label: "Office router"
Profile: VoIP
```

Custom targets are stored locally in settings and included in the measurement schedule alongside Slogr reflectors. They are not sent to `GET /v1/reflectors` — they exist only on this device.

When the desktop agent is CONNECTED and receives a `set_schedule` command via Pub/Sub, the server-pushed schedule **merges** with locally-configured custom targets. Server-pushed targets take priority if there's a conflict (same `path_id`).

## `dest_agent_id` for Reflectors

Slogr mesh reflectors are real Slogr agents. The desktop app detects the Slogr fingerprint in the TWAMP `ServerGreeting` and uses the reflector's real `agent_id` as `dest_agent_id`. No deterministic UUID needed — these are known agents.

For custom targets (user-configured routers/switches), the same deterministic UUID5 formula from R1 applies: `UUID5(NAMESPACE_DNS, "device:{ip}:{port}")`.

## Files

| File | Action |
|------|--------|
| `desktop-core/reflectors/ReflectorDiscoveryClient.kt` | NEW — HTTP client for GET /v1/reflectors |
| `desktop-core/reflectors/ReflectorCache.kt` | NEW — local JSON cache with TTL |
| `desktop-core/reflectors/NearestSelector.kt` | NEW — haversine + optional latency probe |
| `desktop-core/reflectors/Reflector.kt` | NEW — data class |
