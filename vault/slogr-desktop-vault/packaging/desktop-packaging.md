---
status: locked
version: 1.0
depends-on:
  - architecture/compose-desktop
---

# Desktop Packaging

## Distribution Channels

| Channel | Format | Platform |
|---|---|---|
| GitHub Releases | `.msi`, `.dmg` | Both |
| slogr.io/download | Direct download links | Both |
| Chocolatey (future) | `.nupkg` wrapping `.msi` | Windows |
| Homebrew Cask (future) | Formula pointing to `.dmg` | macOS |

## Package Formats

### Windows: MSI Installer

Built with `jpackage` (JDK 21) or Conveyor (hydraulic.dev).

```
slogr-desktop-1.1.0-x64.msi
├── C:\Program Files\Slogr\
│   ├── slogr-desktop.exe              ← jpackage launcher
│   ├── runtime\                        ← trimmed JRE 21 (~50 MB)
│   ├── app\
│   │   └── slogr-desktop.jar          ← fat JAR
│   └── resources\
│       ├── tray-green.png
│       ├── tray-yellow.png
│       ├── tray-red.png
│       └── tray-grey.png
├── C:\ProgramData\Slogr\
│   └── (created at runtime: settings.json, history.db, reflectors.json, .agent_fingerprint)
```

**Installer requirements:**
- Silent install: `msiexec /i slogr-desktop.msi /qn`
- With API key: `msiexec /i slogr-desktop.msi /qn SLOGR_API_KEY=sk_live_abc123`
- Authenticode signed
- Registers auto-start: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\Slogr = "C:\Program Files\Slogr\slogr-desktop.exe" --background`
- Registers custom URI scheme: `HKCR\slogr\` (see `desktop-oauth.md`)
- Creates Start Menu shortcut
- Creates Desktop shortcut (optional, user-selectable during install)
- Uninstall: clean removal via Add/Remove Programs. Removes registry keys, shortcuts, app files. Leaves `%APPDATA%\Slogr\` (user data) intact unless "Remove all data" is checked.

**MSI properties:**

| Property | Default | Purpose |
|---|---|---|
| `SLOGR_API_KEY` | (empty) | Pre-configure API key for enterprise deployment |
| `AUTOSTART` | `1` | Set to `0` to disable auto-start registration |
| `INSTALLDIR` | `C:\Program Files\Slogr\` | Installation directory |

### macOS: DMG Installer

Built with `jpackage` (JDK 21) producing a `.app` bundle, wrapped in a `.dmg`.

```
slogr-desktop-1.1.0.dmg
└── Slogr.app/
    └── Contents/
        ├── Info.plist                  ← app metadata, URI scheme registration
        ├── MacOS/
        │   └── slogr-desktop           ← jpackage launcher
        ├── Resources/
        │   ├── slogr.icns              ← app icon
        │   ├── tray-template.png       ← menu bar icon (template image)
        │   └── tray-template@2x.png    ← retina menu bar icon
        └── runtime/
            └── Contents/Home/          ← trimmed JRE 21 (~50 MB)
```

**DMG requirements:**
- Signed with Apple Developer ID (notarized)
- Drag-to-Applications DMG layout
- Universal binary (x86_64 + aarch64) or separate DMGs per architecture
- `Info.plist` registers `slogr://` URI scheme (see `desktop-oauth.md`)
- Auto-start via LaunchAgent:

```xml
<!-- ~/Library/LaunchAgents/io.slogr.desktop.plist -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>io.slogr.desktop</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Applications/Slogr.app/Contents/MacOS/slogr-desktop</string>
        <string>--background</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
</dict>
</plist>
```

The app writes this plist on first launch if auto-start is enabled. Settings toggle adds/removes the file.

## Installer Size Budget

| Component | Estimated Size |
|---|---|
| Trimmed JRE 21 | ~50 MB |
| Application JAR | ~15 MB |
| MaxMind GeoLite2-ASN MMDB | ~7 MB |
| Resources (icons, etc.) | ~1 MB |
| **Total installer** | **~70-80 MB** |

Conveyor can produce smaller installers (~50 MB) by using more aggressive JRE trimming with `jlink`.

## `--background` Flag

When the app is launched with `--background` (auto-start on login), the main window is not shown. The app starts in tray-only mode, begins measuring, and the tray icon appears. The user can open the window from the tray menu.

```kotlin
fun main(args: Array<String>) {
    val startInBackground = "--background" in args
    application {
        val windowVisible = remember { mutableStateOf(!startInBackground) }
        // ...
        SlogrTray(grade, onOpenWindow = { windowVisible.value = true }, ...)
        if (windowVisible.value) {
            Window(onCloseRequest = { windowVisible.value = false }, ...) {
                MainWindowContent(...)
            }
        }
    }
}
```

## Auto-Update

The app checks for updates on startup and periodically (every 24h):

```
GET https://api.slogr.io/v1/desktop/latest-version
Response: { "version": "1.1.1", "download_url": "https://releases.slogr.io/desktop/...", "release_notes": "..." }
```

If a newer version is available, the app shows a non-intrusive notification: "Slogr v1.1.1 is available. [Update Now] [Later]". Clicking "Update Now" downloads the installer and opens it. The current instance exits to allow the update.

CONNECTED agents can also receive `upgrade` commands via Pub/Sub for enterprise-managed updates.

## CI Pipeline

On tag push (e.g., `v1.1.0`):

```yaml
jobs:
  build:
    - Build desktop JAR (includes contracts + engine + platform + desktop-core + desktop-ui)
    - Run all tests

  package:
    matrix:
      - MSI (Windows x64) — Windows runner with jpackage
      - DMG (macOS universal) — macOS runner with jpackage

  sign:
    - Authenticode sign MSI
    - Apple Developer ID sign + notarize DMG

  publish:
    - Upload to GitHub Releases
    - Update slogr.io/download links
    - Update version check API
```

## Files

| File | Action |
|------|--------|
| `build.gradle.kts` | NEW — Compose Desktop build config, jpackage settings |
| `packaging/windows/slogr-desktop.wxs` | NEW — WiX toolset MSI definition (if not using jpackage MSI) |
| `packaging/macos/Info.plist` | NEW — macOS app bundle metadata |
| `packaging/macos/io.slogr.desktop.plist` | NEW — LaunchAgent template |
