# Windows Installer Notes

## Build MSI

```bash
./gradlew :desktop:packageMsi
```

Output: `desktop/build/compose/binaries/main/msi/Slogr-1.1.0.msi`

Requires: JDK 21+ with jpackage, WiX Toolset 3.x on PATH.

## Silent Install

```cmd
msiexec /i Slogr-1.1.0.msi /qn
```

## Silent Install with API Key

```cmd
msiexec /i Slogr-1.1.0.msi /qn SLOGR_API_KEY=sk_live_abc123
```

The installer sets the `SLOGR_API_KEY` environment variable for the current user.
The app reads this on startup via `System.getenv("SLOGR_API_KEY")`.

## Auto-Start

The app registers itself in the Windows registry on first launch:

```
HKCU\Software\Microsoft\Windows\CurrentVersion\Run
  Slogr = "C:\Program Files\Slogr\slogr-desktop.exe" --background
```

This can be toggled in Settings > Application > "Start Slogr on login".

## Uninstall

Via Settings > Apps > Slogr > Uninstall, or:

```cmd
msiexec /x Slogr-1.1.0.msi /qn
```

User data in `%APPDATA%\Slogr\` is preserved by default.
