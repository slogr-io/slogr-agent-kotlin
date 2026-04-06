---
status: locked
version: 1.0
depends-on:
  - slogr-agent-vault-r2/architecture/three-state-model
  - slogr-agent-vault-r2/integration/registration-r2
  - layer25/key-model
---

# Desktop Registration

## Overview

The desktop app inherits the R2 three-state model and API key registration. The mechanisms are identical to the server agent. The difference is in **how the user provides the key** — through the desktop UI instead of environment variables or CLI flags.

## State Transitions on Desktop

```
┌──────────────────────────────────────────────────────────┐
│  ANONYMOUS                                                │
│  App installed. No sign-in.                               │
│  Measures against free-tier reflectors.                   │
│  Results in local SQLite + UI only.                       │
│  No OTLP. No RabbitMQ.                                    │
│                                                           │
│  UI prompt: "Sign in for free to unlock OTLP export"      │
└────────────────────┬─────────────────────────────────────┘
                     │ User clicks "Sign In" → OAuth flow
                     │ → receives sk_free_* key
                     ▼
┌──────────────────────────────────────────────────────────┐
│  REGISTERED                                               │
│  Signed in. Free key stored in app settings.              │
│  OTLP export enabled.                                     │
│  Same local measurement + SQLite.                         │
│  No RabbitMQ. No Pub/Sub.                                 │
│                                                           │
│  UI prompt: "Upgrade to Pro for SaaS dashboard"           │
└────────────────────┬─────────────────────────────────────┘
                     │ User clicks "Upgrade" → browser opens
                     │ → Stripe checkout → plan activates
                     │ → user copies sk_live_* from SaaS dashboard
                     │ → enters in Settings (or auto-detected via OAuth)
                     ▼
┌──────────────────────────────────────────────────────────┐
│  CONNECTED                                                │
│  Paid key stored in app settings.                         │
│  Auto-registers via POST /v1/agents.                      │
│  RabbitMQ + Pub/Sub + SaaS Agent Directory.               │
│  All profiles, all locations, custom targets.             │
│  "Open Dashboard" button → browser to app.slogr.io       │
└──────────────────────────────────────────────────────────┘
```

## Key Storage on Desktop

The API key is stored in the app settings file, encrypted at rest:

- **Windows:** `%APPDATA%\Slogr\settings.json` — key encrypted via Windows DPAPI (`Cipher` class with `DPAPI` provider or `java.security.KeyStore` with Windows-MY)
- **macOS:** `~/Library/Application Support/Slogr/settings.json` — key stored in macOS Keychain via `java.security.KeyStore` with KeychainStore provider

The key is never written to disk in plaintext. On platforms where OS-level encryption is unavailable, fall back to AES-256 encryption with a machine-derived key (SHA256 of fingerprint).

## State Determination on Desktop

Same logic as the server agent, but reads from settings file instead of environment variable:

```kotlin
fun determineState(): AgentState {
    val apiKey = desktopSettings.apiKey   // from encrypted settings
        ?: System.getenv("SLOGR_API_KEY") // fallback to env var (enterprise deployment via Intune/SCCM)
    return when {
        apiKey == null                    -> ANONYMOUS
        apiKey.startsWith("sk_free_")    -> REGISTERED
        apiKey.startsWith("sk_live_")    -> CONNECTED
        else                             -> ANONYMOUS
    }
}
```

Environment variable takes precedence over settings file when present. This supports enterprise IT pushing keys via management tools (SCCM, Intune, Jamf) without requiring the user to sign in through the UI.

## ANONYMOUS → REGISTERED Transition

1. User clicks "Sign In" in the app
2. Desktop OAuth flow starts (see `desktop-oauth.md`)
3. OAuth completes → app receives authorization code
4. App exchanges code with BFF for user token
5. BFF creates user + tenant (Free plan) if new
6. BFF generates `sk_free_*` key
7. BFF returns key to app
8. App stores key in encrypted settings
9. App re-evaluates state → REGISTERED
10. OTLP exporter activates
11. UI updates: sign-in prompt disappears, user info shown

## REGISTERED → CONNECTED Transition

1. User clicks "Upgrade to Pro" in the app
2. Browser opens `https://app.slogr.io/upgrade` (pre-authenticated via deep link token)
3. User completes Stripe checkout
4. SaaS activates Pro plan, generates `sk_live_*` key
5. Two paths to get the key into the app:
   a. **Manual:** User copies key from SaaS dashboard, pastes in Settings → Account → "Enter API Key"
   b. **Automatic (future):** App polls `GET /v1/keys/check-upgrade` periodically after opening upgrade page. If plan upgraded, response includes new key.
6. App stores `sk_live_*` key in encrypted settings
7. App re-evaluates state → CONNECTED
8. App calls `POST /v1/agents` (auto-registration)
9. RabbitMQ + Pub/Sub activate
10. Freemium limits lifted (all profiles, all locations, custom targets)

## CONNECTED → ANONYMOUS Transition (Sign Out)

1. User clicks "Sign Out" in Settings
2. App deletes stored key from encrypted settings
3. App closes RabbitMQ and Pub/Sub connections
4. App re-evaluates state → ANONYMOUS
5. Measurement continues against free-tier reflectors
6. Freemium limits re-applied
7. Local SQLite history preserved (still shows last 24h)

## Enterprise Deployment (IT-Managed)

IT admin pushes `sk_live_*` key via deployment tool:

- **Windows:** MSI custom property or registry key
  ```
  msiexec /i slogr-desktop.msi /qn SLOGR_API_KEY=sk_live_abc123
  ```
  Installer writes key to `HKLM\Software\Slogr\ApiKey`. App reads on startup.

- **macOS:** Managed preferences via MDM (Jamf, Intune)
  ```xml
  <dict>
    <key>SLOGR_API_KEY</key>
    <string>sk_live_abc123</string>
  </dict>
  ```

In enterprise deployment, the sign-in UI is unnecessary — the key arrives via management tool. The app detects the key on startup and transitions directly to CONNECTED.

## Files

| File | Action |
|------|--------|
| `desktop-core/registration/DesktopStateManager.kt` | NEW — state determination from settings + env var |
| `desktop-core/registration/DesktopRegistrar.kt` | NEW — wraps L1 `ApiKeyRegistrar`, adds desktop-specific key sourcing |
| `desktop-core/registration/EncryptedKeyStore.kt` | NEW — OS-specific encrypted key storage |
