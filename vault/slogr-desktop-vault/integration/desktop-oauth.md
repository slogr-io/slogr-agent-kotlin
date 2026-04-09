---
status: locked
version: 1.0
depends-on:
  - integration/desktop-registration
---

# Desktop OAuth Flow

## Overview

The desktop app uses browser-based OAuth for sign-in. The app never renders a webview — it opens the system browser and receives the callback via localhost HTTP server or custom URI scheme.

## Two Callback Mechanisms

### Primary: Localhost Callback

1. App starts ephemeral HTTP server on `http://localhost:{random_port}/callback`
2. App opens system browser to OAuth authorize URL with `redirect_uri=http://localhost:{port}/callback`
3. User authenticates in browser (LinkedIn or Google)
4. OAuth provider redirects browser to `http://localhost:{port}/callback?code=...`
5. App's HTTP server receives the code, stops the server
6. App exchanges code for tokens with the BFF

**Advantages:** Works everywhere. No URI scheme registration needed. Standard PKCE flow.

### Fallback: Custom URI Scheme

1. App registers `slogr://` URI scheme during installation
2. App opens system browser to OAuth authorize URL with `redirect_uri=slogr://callback`
3. User authenticates in browser
4. OAuth provider redirects to `slogr://callback?code=...`
5. OS routes the URI to the running app instance
6. App extracts the code and exchanges for tokens

**When used:** When localhost HTTP server fails to bind (port conflict, corporate proxy intercept, security software blocking localhost).

### Registration of Custom URI Scheme

**Windows (during MSI install):**
```
HKCR\slogr\(Default) = "URL:Slogr"
HKCR\slogr\URL Protocol = ""
HKCR\slogr\shell\open\command\(Default) = "C:\Program Files\Slogr\slogr-desktop.exe" "--uri" "%1"
```

**macOS (in Info.plist):**
```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array>
      <string>slogr</string>
    </array>
    <key>CFBundleURLName</key>
    <string>io.slogr.desktop</string>
  </dict>
</array>
```

## OAuth Flow Sequence

```
Desktop App                    System Browser                 api.slogr.io (BFF)
───────────                    ──────────────                 ─────────────────
1. Generate PKCE code_verifier
   + code_challenge

2. Start localhost:PORT/callback
   (or prepare slogr:// handler)

3. Open browser ──────────────►
   https://api.slogr.io/auth/authorize
   ?provider=linkedin
   &redirect_uri=http://localhost:PORT/callback
   &code_challenge=...
   &state=random_nonce
                                4. User sees LinkedIn
                                   sign-in page
                                5. User authenticates ────────► 6. Validate OAuth
                                                                7. Generate auth code

                                8. Redirect ◄─────────────────
                                   http://localhost:PORT/callback
                                   ?code=AUTH_CODE
                                   &state=random_nonce

9. Receive code on localhost
   (or slogr:// handler)
10. Verify state matches nonce

11. POST /auth/token ──────────────────────────────────────► 12. Exchange code
    code=AUTH_CODE                                               + code_verifier
    code_verifier=...                                         13. Create user/tenant
                                                                  if new
                                                              14. Generate sk_free_*
                                                                  key

15. Receive response: ◄──────────────────────────────────────
    { api_key: "sk_free_...",
      user: { name, email },
      tenant_id: "..." }

16. Store key in EncryptedKeyStore
17. Transition to REGISTERED
18. Close localhost server
```

## BFF Endpoints (New for Desktop OAuth)

### GET /auth/authorize

Redirects to LinkedIn or Google OAuth. Standard OAuth 2.0 authorization endpoint with PKCE.

Query parameters:
- `provider`: `linkedin` or `google`
- `redirect_uri`: `http://localhost:{port}/callback` or `slogr://callback`
- `code_challenge`: PKCE S256
- `state`: random nonce for CSRF protection

### POST /auth/token

Token exchange. Returns API key + user info.

```json
// Request
{
  "code": "<authorization_code>",
  "code_verifier": "<PKCE verifier>",
  "redirect_uri": "http://localhost:PORT/callback"
}

// Response 200
{
  "api_key": "sk_free_a1b2c3d4...",
  "key_type": "free",
  "user": {
    "id": "uuid",
    "name": "Nasim Khan",
    "email": "nasim@example.com"
  },
  "tenant_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
}
```

If the user already has a `sk_live_*` key (existing Pro customer signing in on desktop), the response returns the live key instead:

```json
{
  "api_key": "sk_live_9f8e7d6c...",
  "key_type": "live",
  ...
}
```

The desktop app then transitions directly to CONNECTED.

## Security

- **PKCE mandatory.** Prevents authorization code interception.
- **State parameter mandatory.** Prevents CSRF.
- **Localhost callback on random port.** Prevents port prediction attacks.
- **Callback server lifetime:** Maximum 5 minutes. If no callback received, server stops and UI shows timeout error.
- **Code exchange happens server-side (BFF).** Client secrets never touch the desktop app.

## Files

| File | Action |
|------|--------|
| `desktop-core/oauth/DesktopOAuthFlow.kt` | NEW — orchestrates the full flow |
| `desktop-core/oauth/LocalCallbackServer.kt` | NEW — ephemeral localhost HTTP server |
| `desktop-core/oauth/CustomUriHandler.kt` | NEW — slogr:// URI scheme handler |
| `desktop-core/oauth/PkceGenerator.kt` | NEW — code_verifier + code_challenge |
