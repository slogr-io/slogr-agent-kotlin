---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
  - cli/cli-interface
---

# Packaging

## AWS Marketplace (R1)

### AMI Specification

| Property | Value |
|----------|-------|
| Base OS | Amazon Linux 2023 |
| Instance type default | t3.micro |
| Architecture | amd64 (arm64 AMI as secondary) |
| Installed software | JRE 21 (Amazon Corretto), slogr-agent binary, libslogr-native.so, MaxMind GeoLite2-ASN.mmdb |
| Systemd service | `slogr-agent.service` (Type=notify, Restart=always, TimeoutStopSec=45) |
| User | `slogr` (no shell, no login) |
| Capabilities | `AmbientCapabilities=CAP_NET_RAW CAP_NET_BIND_SERVICE` in systemd unit (port 862 is privileged) |
| Data directory | `/opt/slogr/data/` |
| Log output | journald (structured JSON) |

### CloudFormation Template

Parameters:
- `BootstrapToken` (String, NoEcho) — pasted from SaaS UI
- `InstanceType` (String, default `t3.micro`)
- `SubnetId` (AWS::EC2::Subnet::Id)
- `SecurityGroupId` (AWS::EC2::SecurityGroup::Id)

Resources:
- ASG (size 1) referencing Launch Template
- Launch Template: AMI ID from SSM parameter `/slogr/agent/latest-ami-id`
- UserData passes `SLOGR_BOOTSTRAP_TOKEN` to the agent
- IAM instance profile: no AWS permissions needed (agent talks to Slogr, not AWS services)

SSM parameter `/slogr/agent/latest-ami-id` is updated by Slogr CI when a new AMI is published. Customers update by cycling the ASG instance.

### Security Group Requirements

| Direction | Port | Protocol | Source/Dest | Purpose |
|-----------|------|----------|-------------|---------|
| Outbound | 443 | TCP | `api.slogr.io` | Registration, token refresh |
| Outbound | 5671 | TCP | RabbitMQ broker | Measurement publishing |
| Outbound | 443 | TCP | GCP Pub/Sub API | Command polling |
| Outbound | 862 | TCP+UDP | TWAMP targets | Measurement sessions |
| Inbound | 862 | TCP+UDP | TWAMP sources (if responder enabled) | Accept sessions from routers |

### AMI Build (Packer)

```hcl
source "amazon-ebs" "slogr-agent" {
  ami_name      = "slogr-agent-{{timestamp}}"
  instance_type = "t3.micro"
  source_ami_filter {
    filters = { "name" = "al2023-ami-*-x86_64" }
    owners  = ["amazon"]
  }
}

build {
  provisioner "shell" {
    inline = [
      "sudo yum install -y java-21-amazon-corretto-headless",
      "sudo useradd -r -s /sbin/nologin slogr",
      "sudo mkdir -p /opt/slogr/{bin,lib,data}",
      "sudo chown slogr:slogr /opt/slogr/data",
    ]
  }
  provisioner "file" {
    source      = "build/slogr-agent.jar"
    destination = "/opt/slogr/bin/slogr-agent.jar"
  }
  provisioner "file" {
    source      = "build/libslogr-native.so"
    destination = "/opt/slogr/lib/libslogr-native.so"
  }
  provisioner "file" {
    source      = "deploy/slogr-agent.service"
    destination = "/etc/systemd/system/slogr-agent.service"
  }
  provisioner "shell" {
    inline = [
      "sudo systemctl daemon-reload",
      "sudo systemctl enable slogr-agent",
    ]
  }
}
```

## GCP Marketplace / Azure Marketplace (R3)

Same pattern: GCE image / Azure VM image built with equivalent tooling (Packer supports all three). CloudFormation → Deployment Manager (GCP) / ARM template (Azure). Bootstrap token delivery: GCP startup-script metadata, Azure custom data.

## Docker Image

```dockerfile
FROM amazoncorretto:21-alpine
RUN adduser -D -s /sbin/nologin slogr
COPY build/slogr-agent.jar /opt/slogr/bin/
COPY build/libslogr-native.so /opt/slogr/lib/
COPY data/GeoLite2-ASN.mmdb /opt/slogr/data/
RUN mkdir -p /opt/slogr/data/wal && chown -R slogr:slogr /opt/slogr/data
USER slogr
ENV SLOGR_NATIVE_DIR=/opt/slogr/lib
ENV SLOGR_DATA_DIR=/opt/slogr/data
ENTRYPOINT ["java", "-Xmx384m", "-Dslogr.native.dir=/opt/slogr/lib", "-jar", "/opt/slogr/bin/slogr-agent.jar"]
CMD ["daemon"]
```

Multi-arch: linux/amd64 + linux/arm64. Build with `docker buildx`.

Published to: Docker Hub (`slogr/agent`) and ECR Public.

**Note:** Container must run with `--cap-add=NET_RAW --cap-add=NET_BIND_SERVICE` for traceroute, TWAMP DSCP, and binding to port 862.

## Standalone Binary (yum/apt/brew)

### RPM (yum/dnf)

```
/opt/slogr/bin/slogr-agent.jar
/opt/slogr/lib/libslogr-native.so
/etc/systemd/system/slogr-agent.service
/usr/bin/slogr-agent                    ← wrapper script
```

Wrapper script (`/usr/bin/slogr-agent`):
```bash
#!/bin/bash
exec java -Xmx384m -Dslogr.native.dir=/opt/slogr/lib -jar /opt/slogr/bin/slogr-agent.jar "$@"
```

Depends on: `java-21-amazon-corretto-headless` or equivalent.

### DEB (apt)

Same layout, `.deb` packaging. Depends on `openjdk-21-jre-headless`.

### Homebrew (macOS)

```ruby
class SlogrAgent < Formula
  desc "Network measurement agent with TWAMP and traceroute"
  homepage "https://slogr.io"
  url "https://releases.slogr.io/agent/1.0.0/slogr-agent-1.0.0-macos.tar.gz"
  sha256 "abc123..."

  depends_on "openjdk@21"

  def install
    libexec.install "slogr-agent.jar"
    libexec.install "libslogr-native.dylib"
    (bin/"slogr-agent").write_env_script libexec/"run.sh",
      SLOGR_NATIVE_DIR: libexec.to_s
  end
end
```

### Chocolatey (Windows, R3)

```xml
<?xml version="1.0" encoding="utf-8"?>
<package>
  <metadata>
    <id>slogr-agent</id>
    <version>1.0.0</version>
    <title>Slogr Network Measurement Agent</title>
    <authors>Slogr</authors>
    <description>TWAMP and traceroute network measurement agent</description>
    <dependencies>
      <dependency id="corretto21jdk" version="21.0.0" />
    </dependencies>
  </metadata>
</package>
```

Installs to `%ProgramFiles%\slogr\`. Wrapper `slogr-agent.cmd`:
```cmd
@echo off
java -Xmx384m -Dslogr.native.dir="%ProgramFiles%\slogr\lib" -jar "%ProgramFiles%\slogr\slogr-agent.jar" %*
```

### Windows Development Mode (immediate, no installer)

For running on a Windows laptop without any packaging — just a JDK and the fat JAR:

```powershell
# No install needed. Download or build the fat JAR, then:
java -jar slogr-agent.jar version
java -jar slogr-agent.jar check 10.0.1.5 --profile voip
java -jar slogr-agent.jar check 10.0.1.5 --traceroute --format json
```

Runs in pure-Java fallback mode (no native library needed). See `modules/jni-native.md` for what's available in fallback mode. Data stored in `%APPDATA%\slogr\`.

---

# Resource Budget (t3.micro)

| Resource | Budget | Notes |
|----------|--------|-------|
| JVM heap | 384 MB (`-Xmx384m`) | Leaves ~128 MB for OS + JVM off-heap + native code |
| JVM metaspace | 64 MB default | Kotlin + libs fit easily |
| Native memory | ~20 MB | JNI sockets, libslogr-native |
| MaxMind MMDB | ~7 MB | Memory-mapped, doesn't count against heap |
| WAL disk | 100 MB max | Bounded, oldest-first eviction |
| Log files | 50 MB max | 3 rotations × ~17 MB |
| Total disk | < 300 MB | Binary + JRE + data + logs |
| CPU (idle) | < 1% | Sleeping between test intervals |
| CPU (active) | 30-60% of 2 vCPU | During concurrent TWAMP sessions |
| Network | ~10 KB/s average | Test packets + results publishing |
| Startup time | < 10 seconds | JVM + module init + registration |
