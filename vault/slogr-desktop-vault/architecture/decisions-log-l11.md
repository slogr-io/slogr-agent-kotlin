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

## ADR-060: Traffic-Centric Dashboard

**Status:** Locked
**Context:** The original main screen showed location cards (per-reflector results). Users care about "is my connection good for gaming?" not "what is my RTT to us-east-1?"
**Decision:** Dashboard shows 3 user-selected traffic type icons with green/red indicators. One TWAMP measurement evaluated against 3 SLA profiles simultaneously. Server/location management moves to Settings view.
**Consequence:** ProfileManager updated to support 3 concurrent active profiles. Main window redesigned with sidebar navigation.

## ADR-061: Runtime Server Configuration (No Hardcoded Reflectors)

**Status:** Locked
**Context:** Slogr's public reflector infrastructure is not deployed yet. The GET /v1/reflectors API does not exist. Desktop users need to add their own TWAMP servers.
**Decision:** The app ships with zero built-in servers. All servers are added by the user at runtime via Settings -> Servers -> "Add Server." Servers are stored in settings.json and persist across restarts. When Slogr deploys public reflectors, a future update will add auto-discovery via GET /v1/reflectors.
**Consequence:** First-launch experience requires the user to add a server before seeing results. The empty state guides them to Settings.

## ADR-062: Minimal Tray Menu

**Status:** Locked
**Context:** The original tray menu had 8+ items including profile submenus and settings. Too complex for a right-click menu. The window should be the single place for all interaction.
**Decision:** Tray menu has exactly 5 items: grade label, timestamp label, Run Test Now, Open Slogr, Quit. When no servers configured: 3 items (no-servers label, Open Slogr, Quit). Everything else happens in the window.
**Consequence:** Simpler tray, fewer AWT rendering issues, cleaner UX.

## ADR-063: Light Theme

**Status:** Locked
**Context:** The dark theme made the Slogr logo (dark grey text) invisible. Dark-on-dark text contrast issues persisted across multiple fix attempts.
**Decision:** Switch to light theme. White backgrounds, dark text (#212121), Slogr green (#4CAF50) accent. Logo, text, and UI elements all clearly visible on light backgrounds.
**Consequence:** All composable colors updated. Theme is light. Dark theme may be offered as an option in a future release.

## ADR-064: Compose Popup Window for Tray Menu

**Status:** Locked
**Context:** AWT PopupMenu on Windows produces overlapping text at various DPI scaling levels. Two fix attempts (emoji removal, plain text) both failed.
**Decision:** Replace AWT PopupMenu with a Compose Desktop borderless Window that appears on tray icon right-click. Dismisses on focus loss. Full control over fonts, spacing, and rendering.
**Consequence:** Tray icon remains AWT TrayIcon. Menu rendering is pixel-perfect and DPI-independent.

## ADR-065: Per-Traffic-Type TWAMP Sessions with Real DSCP

**Status:** Locked
**Context:** A single TWAMP session evaluated against 3 SLA thresholds doesn't show how the ISP actually treats different traffic classes. Real VoIP and gaming traffic have different DSCP markings, packet sizes, and intervals.
**Decision:** Each measurement cycle runs 3 sequential TWAMP sessions — one per active traffic type — with the actual packet signature (DSCP value, packet size, interval, count) of that traffic class. JavaUdpTransport.setTos() calls DatagramSocket.setTrafficClass() to mark packets.
**Consequence:** Test time ~9 seconds (3 × 3s). Results show real per-class network treatment. If ISP strips DSCP, the user sees that their traffic gets best-effort treatment — which is the accurate result.

## ADR-066: Accordion Settings

**Status:** Locked
**Context:** Settings as a long scrollable page hid sections below the fold. Users didn't know what options existed without scrolling.
**Decision:** 4 collapsible accordion sections (Traffic Types, Servers, Application, About) all collapsed by default. Click header to expand. Multiple can be open at once.
**Consequence:** All settings categories visible at a glance. Each section expands to show its content inline.

## ADR-067: Share Results (Zip Export)

**Status:** Locked
**Context:** Users need to share connection quality data with network administrators for troubleshooting.
**Decision:** "Share Results" button in About section. Generates a .zip with: test_results.json (last 20 tests), diagnostics.json, system_info.json, and summary.txt (human-readable). User picks save location via file dialog.
**Consequence:** Non-technical users can export and email results. Network admins get structured JSON data.

## ADR-068: Update Notification (No Silent Install)

**Status:** Locked
**Context:** Desktop agents need to know when a new version is available. However, silent auto-download and auto-install is a supply chain attack vector — if the update server (slogr.io) is compromised, attackers could push malicious code to every desktop agent.
**Decision:** Agent checks `https://slogr.io/desktop/update.json` on startup + every 24h. If newer version found, shows a persistent green banner at the top of the window: "A new version of Slogr is available that improves performance. Click here to download." The banner cannot be dismissed (stays until user updates or server returns no update). Clicking opens the download URL in the system browser. The agent NEVER downloads or executes any file automatically.
**Consequence:** Users must manually download and install updates via their browser (HTTPS, verified domain). No automatic code execution. The banner is a gentle nudge, not a forced update.

**Update check URL:** `https://slogr.io/desktop/update.json`

**update.json format:**
```json
{
  "version": "1.2.0",
  "download_url": "https://slogr.io/desktop/Slogr-1.2.0.msi"
}
```

**If the URL returns 404 or any error:** silently ignored, no banner shown.

## ADR-069: Active Server Selection

**Status:** Locked
**Context:** Users can add multiple TWAMP servers, but only one should be tested at a time to avoid confusing results and unnecessary traffic.
**Decision:** Dropdown in Servers section to select which server is active. Only the active server is measured. Others remain in the list for easy switching. When first server is added, it becomes active automatically.
**Consequence:** Clear single-server results. Server list serves as a favorites/recent list for future use.
