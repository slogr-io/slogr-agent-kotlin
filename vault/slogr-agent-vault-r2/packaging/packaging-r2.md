# Packaging (R2)

**Status:** Locked
**Replaces:** R1 `packaging/packaging.md`

---

## Distribution Channels

| Channel | Cost | Purpose |
|---------|------|---------|
| GitHub Releases | $0 | JAR, RPM, DEB, MSI, PKG — direct download |
| Packagecloud (free tier) | $0 (2GB storage, 10GB bandwidth) | yum + apt repos — `yum install slogr-agent` |
| ghcr.io | $0 | Primary Docker registry |
| Docker Hub (free tier) | $0 | Secondary Docker registry (discoverability) |
| Homebrew tap | $0 | macOS developer install |
| Chocolatey | $0 | Windows developer install |

## Package Formats

### 1. RPM (yum/dnf) — RHEL, CentOS, Amazon Linux, Fedora

```
slogr-agent-1.0.0-1.x86_64.rpm
├── /usr/bin/slogr-agent                    → fat JAR or jpackage binary
├── /etc/slogr/agent.yaml                   → default config (empty, all defaults)
├── /usr/lib/slogr/libslogr_native.so       → JNI C library
├── /usr/lib/systemd/system/slogr-agent.service  → systemd unit
├── /var/lib/slogr/                         → credential store + WAL + key cache
└── /usr/share/doc/slogr-agent/LICENSE      → Elastic License 2.0 or BSL 1.1
```

GPG signed. Published to Packagecloud.

### 2. DEB (apt) — Ubuntu, Debian

Same file layout as RPM, different package format. GPG signed. Published to Packagecloud.

### 3. MSI (Windows Installer) — Windows Server, Desktop, VDI

```
slogr-agent-1.0.0-x64.msi
├── C:\Program Files\Slogr\slogr-agent.exe  → jpackage binary
├── C:\Program Files\Slogr\native\slogr_native.dll → JNI C library
├── C:\ProgramData\Slogr\agent.yaml         → default config
├── C:\ProgramData\Slogr\                   → credential store + WAL + key cache
└── LICENSE
```

Requirements:
- Silent install: `msiexec /i slogr-agent.msi /qn SLOGR_API_KEY=sk_live_abc123`
- `SLOGR_API_KEY` accepted as MSI property
- Registers Windows Service (`slogr-agent`) automatically
- Exit code 0 on success, 1603 on failure
- Authenticode signed

### 4. PKG (macOS Installer)

```
slogr-agent-1.0.0.pkg
├── /usr/local/bin/slogr-agent              → jpackage binary or fat JAR wrapper script
├── /usr/local/lib/slogr/libslogr_native.dylib → JNI C library
├── /etc/slogr/agent.yaml                   → default config
├── /Library/LaunchDaemons/io.slogr.agent.plist → launchd service
├── /var/lib/slogr/                         → credential store + WAL + key cache
└── LICENSE
```

Signed with Apple Developer ID. Post-install script runs `slogr-agent connect` if `SLOGR_API_KEY` is in environment.

### 5. Docker Image

```dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY slogr-agent-all.jar /opt/slogr/slogr-agent.jar
COPY libslogr_native.so /opt/slogr/native/
ENV SLOGR_NATIVE_DIR=/opt/slogr/native
ENTRYPOINT ["java", "-jar", "/opt/slogr/slogr-agent.jar", "daemon"]
```

Published to `ghcr.io/slogr/agent:latest` and `docker.io/slogr/agent:latest`.

Tags: `latest`, `1.0.0`, `1.0`, `1`.

Requires `--cap-add NET_RAW --cap-add NET_BIND_SERVICE`.

Auto-connects when `SLOGR_API_KEY` env var is set. No manual `connect` step needed.

### 6. Helm Chart

```
slogr/agent (Helm chart)
├── Chart.yaml
├── values.yaml
├── templates/
│   ├── daemonset.yaml          → one agent per K8s node
│   ├── configmap.yaml          → agent.yaml config
│   ├── secret.yaml             → SLOGR_API_KEY (if not using existingSecret)
│   └── serviceaccount.yaml
```

```yaml
# values.yaml
slogr:
  apiKey: ""                    # set via --set or existingSecret
  existingSecret: ""            # name of existing K8s Secret
  secretKey: "api-key"          # key within the Secret
  config:
    traceroute_max_hops: 30
    traceroute_heartbeat_interval_cycles: 6
```

Install:
```bash
helm install slogr-agent slogr/agent \
  --set slogr.apiKey=sk_live_abc123 \
  --namespace monitoring
```

### 7. Homebrew Formula

```ruby
class SlogrAgent < Formula
  desc "Network path quality monitoring agent"
  homepage "https://slogr.io"
  url "https://github.com/slogr/agent-kotlin/releases/download/v1.0.0/slogr-agent-1.0.0-macos-amd64.tar.gz"
  sha256 "..."

  depends_on "openjdk@21"

  def install
    bin.install "slogr-agent"
    lib.install "libslogr_native.dylib"
  end

  service do
    run [opt_bin/"slogr-agent", "daemon"]
    keep_alive true
    working_dir var/"lib/slogr"
    log_path var/"log/slogr-agent.log"
  end
end
```

Hosted in `slogr/homebrew-tap` GitHub repo.

### 8. Chocolatey Package

```xml
<!-- slogr-agent.nuspec -->
<package>
  <metadata>
    <id>slogr-agent</id>
    <version>1.0.0</version>
    <title>Slogr Agent</title>
    <description>Network path quality monitoring agent</description>
    <projectUrl>https://slogr.io</projectUrl>
    <licenseUrl>https://github.com/slogr/agent-kotlin/blob/main/LICENSE</licenseUrl>
  </metadata>
</package>
```

Install: `choco install slogr-agent --params "'/ApiKey:sk_live_abc123'"`

### 9. Universal Shell Installer

```bash
curl -fsSL https://releases.slogr.io/install.sh | sh
```

The script must:
1. Detect OS (Linux/macOS) and architecture (amd64/arm64)
2. Detect package manager (yum/apt/brew/none)
3. Install via package manager if available, direct download if not
4. If `SLOGR_API_KEY` is set in environment, run `slogr-agent connect` automatically
5. Register as system service (systemd/launchd)
6. Print success message with version and instructions

## All Packages Must

- Install silently with no interactive prompts
- Accept `SLOGR_API_KEY` as install parameter or environment variable
- Register as a system service automatically
- Support clean uninstall via package manager
- Be signed (RPM: GPG, DEB: GPG, MSI: Authenticode, PKG: Apple Developer ID, APT: GPG)
- Include LICENSE file (Elastic License 2.0 or BSL 1.1)

## CI Pipeline (GitHub Actions)

On tag push (e.g., `v1.0.0`):

```yaml
jobs:
  build:
    - Build fat JAR
    - Build JNI native libraries (linux-amd64, linux-arm64, macos-amd64, macos-arm64, windows-amd64)
    - Run all tests

  package:
    needs: build
    matrix:
      - RPM (x86_64, aarch64)
      - DEB (amd64, arm64)
      - MSI (x64)
      - PKG (universal)
      - Docker (multi-arch: amd64, arm64)

  publish:
    needs: package
    - Upload to GitHub Releases
    - Push RPM/DEB to Packagecloud
    - Push Docker to ghcr.io + Docker Hub
    - Update Homebrew tap formula
    - Push Chocolatey package
    - Update install.sh with new version
```
