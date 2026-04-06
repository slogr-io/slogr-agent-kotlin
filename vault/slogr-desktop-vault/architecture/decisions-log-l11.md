---
status: locked
version: 1.0
depends-on:
  - slogr-agent-vault/architecture/decisions-log
  - slogr-agent-vault-r2/architecture/decisions-log-r2
---

# L1.1 Decisions Log

Extends R1 ADRs 001-020 and R2 ADRs 021-040. L1.1 adds ADR-050 through ADR-059.

---

## ADR-050: Compose Desktop for GUI

**Status:** Locked
**Context:** The desktop app needs a cross-platform GUI with system tray support. Options: Compose Desktop (JetBrains, Kotlin), Electron, JavaFX, GraalVM + native toolkit.
**Decision:** Compose Desktop (JetBrains). Kotlin-native UI framework. Includes `Tray` composable for system tray icons on Windows and macOS. Packages into platform installers via `jpackage`. Reuses 100% of the Kotlin measurement engine from L1 R1/R2.
**Consequence:** Single codebase for both platforms. Bundled trimmed JRE (~60-80 MB installer). No separate JRE install required by the user.

## ADR-051: Pure-Java Fallback Mode Always (Desktop)

**Status:** Locked
**Context:** The server agent uses JNI native libraries for raw sockets, kernel timestamps, and ICMP traceroute. These require `CAP_NET_RAW` (Linux) or admin privileges (Windows/macOS). Desktop users should not need elevated privileges.
**Decision:** Desktop app always runs in pure-Java fallback mode. No JNI library is loaded. TWAMP via `DatagramSocket`. Traceroute via `ProcessBuilder` wrapping OS `tracert`/`traceroute`. No TTL, no DSCP, no kernel timestamps.
**Consequence:** Millisecond-precision timestamps instead of microsecond. No DSCP QoS marking. Acceptable for consumer/prosumer use case.

## ADR-052: macOS UDP-Only Traceroute

**Status:** Locked
**Context:** ICMP traceroute on macOS requires root or a privileged helper binary (SMJobBless). UDP traceroute (`traceroute -U`) works without root.
**Decision:** Use UDP mode traceroute on macOS. Accept that some hops may not respond to UDP probes (they do respond to ICMP). This is a known limitation documented in the UI.
**Consequence:** macOS traceroute may show more `*` (timeout) hops than Windows ICMP traceroute. ASN path analysis still works for responding hops.

## ADR-053: Mesh Agents as Public Reflectors

**Status:** Locked
**Context:** Desktop agents need TWAMP reflectors to test against. Options: dedicated reflector infrastructure, reuse mesh agents.
**Decision:** Mesh agents (tenant `00001`) serve as public TWAMP reflectors. A new `is_public_reflector` flag in the agent record identifies which mesh agents are discoverable. New `GET /v1/reflectors` API endpoint returns the list with coordinates.
**Consequence:** No new infrastructure for launch. Reflector capacity scales with the mesh fleet. Dedicated reflectors can be added later under the same API if needed.

## ADR-054: Reflector Discovery API (Unauthenticated)

**Status:** Locked
**Context:** ANONYMOUS desktop users (no key) need to discover reflectors. The API endpoint must work without authentication.
**Decision:** `GET /v1/reflectors` is public, rate-limited by IP (10 requests/minute). Returns reflector list with region, coordinates, host, port, and tier. Desktop app caches the response for 24 hours.
**Consequence:** New endpoint in L3 BFF. No auth middleware. IP-based rate limiting via Cloud Run or API gateway.

## ADR-055: Close Window = Minimize to Tray

**Status:** Locked
**Context:** Desktop apps that run background services need to stay alive when the window is closed. Options: actually quit, minimize to tray, ask user preference.
**Decision:** Clicking the window close button (X) minimizes to tray. The app continues measuring in the background. "Quit" in the tray menu or app menu exits the process.
**Consequence:** Standard behavior matching Discord, Slack, Tailscale. User expectation is correct on first use.

## ADR-056: Auto-Start Default ON

**Status:** Locked
**Context:** The desktop agent provides value by running continuously. If it doesn't auto-start, users forget about it.
**Decision:** Auto-start on login is enabled by default during installation. User can disable in Settings. Windows: registry key `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`. macOS: LaunchAgent plist in `~/Library/LaunchAgents/`.
**Consequence:** App launches on login with the window hidden (tray-only). User sees the tray icon and can open the window from the tray menu.

## ADR-057: 24-Hour Local SQLite History

**Status:** Locked
**Context:** Free/anonymous users have no SaaS history. Showing only live results reduces perceived value. A small local history gives users a reason to keep the app running.
**Decision:** All measurement results are stored in a local SQLite database for 24 hours, regardless of agent state. A background job prunes entries older than 24h every hour. CONNECTED users also have SaaS history (unlimited) — SQLite is purely for the local UI.
**Consequence:** ~1-2 MB database file per day. Stored in app data directory (`%APPDATA%\Slogr\` or `~/Library/Application Support/Slogr/`).

## ADR-058: Desktop OAuth via Browser

**Status:** Locked
**Context:** The desktop app needs a sign-in flow for LinkedIn/Google OAuth. Desktop apps cannot embed OAuth webviews safely (token interception risk). Standard approach: open system browser, receive callback.
**Decision:** Both mechanisms supported — app picks based on OS and environment:
- **Localhost callback** (primary): App starts ephemeral HTTP server on `http://localhost:{random_port}/callback`. Browser redirects to this URL after OAuth. Works on all platforms.
- **Custom URI scheme** (fallback): Register `slogr://callback` URI scheme during install. Browser redirects to `slogr://callback?code=...`. App receives via OS handler. Used when localhost is blocked by corporate proxy or security software.
**Consequence:** Installer registers `slogr://` URI scheme on both platforms. App tries localhost first, falls back to custom URI scheme on failure.

## ADR-059: Freemium Profile and Location Limits

**Status:** Locked
**Context:** Free users should get enough value to stay engaged but limited enough to incentivize upgrade.
**Decision:** Free tier limits:
- **Profiles:** Internet + one additional (user picks which). Paid: all profiles.
- **Locations:** Up to 3 Slogr reflectors (nearest auto-selected). Paid: all reflectors + custom targets.
- **History:** 24h local SQLite. Paid: unlimited SaaS history.
- **Traceroute:** Included in all tiers.
- **Custom targets:** Paid only. Free users can only test against Slogr reflectors.
**Consequence:** Freemium gating is client-side enforcement. The `GET /v1/reflectors` response includes a `tier` field. The `GET /v1/keys/validate` response could include plan limits. Server-side enforcement added later if needed.
