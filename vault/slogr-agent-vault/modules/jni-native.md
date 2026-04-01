---
status: locked
version: 1.0
depends-on:
  - architecture/decisions-log
claude-code-context:
  - "Read when working on C code or JNI bindings"
---

# JNI Native Library

## Overview

Single shared library (`libslogr-native`) containing all native code. Two source files, one compiled output.

## Source Files

### twampUdp.c (RFC 5357 UDP operations, reference: Java agent `libudp/`)

Functions to implement (reference: Java agent JNI layer for protocol understanding):

| Function | Purpose |
|----------|---------|
| `openSocket()` | Create raw SOCK_DGRAM socket |
| `closeSocket()` | Close socket |
| `sendPacket()` | Send TWAMP test packet |
| `recvPacket()` | Receive with `recvmsg()` + ancillary data |
| `setSocketTos()` | Set IP_TOS (DSCP) on socket |
| `getReceivedTtl()` | Extract TTL from `IP_RECVTTL` ancillary data |
| `setReceiveTtl()` | Enable `IP_RECVTTL` via `setsockopt` |
| `setReceiveTos()` | Enable `IP_RECVTOS` via `setsockopt` |

IPv6 equivalents: `IPV6_UNICAST_HOPS`, `IPV6_RECVHOPLIMIT`.

### traceroute.c (new)

| Function | Purpose |
|----------|---------|
| `icmpProbe(targetIp, ttl, timeoutMs)` | Send ICMP echo with specific TTL, return (hopIp, rttMs) or timeout |
| `udpProbe(targetIp, destPort, ttl, timeoutMs)` | Send UDP packet with specific TTL, return (hopIp, rttMs) or timeout |
| `tcpProbe(targetIp, destPort, ttl, timeoutMs)` | Send TCP SYN with specific TTL, return (hopIp, rttMs) or timeout |

ICMP and UDP probes:
1. Create a raw socket (SOCK_RAW for ICMP, SOCK_DGRAM for UDP)
2. Set TTL via `setsockopt(IP_TTL, ttl)`
3. Send probe
4. Wait for ICMP Time Exceeded (TTL=0) or ICMP Port Unreachable (destination reached)
5. Return structured result to Kotlin: `(hopIp: String, rttMs: Float, reached: Boolean)`

TCP probe (for networks that block ICMP but allow TCP/443):
1. Create a raw socket (SOCK_RAW, IPPROTO_TCP)
2. Set TTL via `setsockopt(IP_TTL, ttl)`
3. Craft a TCP SYN packet manually (IP header + TCP header with SYN flag, random source port, dest port typically 443)
4. Send via `sendto()`
5. Listen on a separate ICMP raw socket for ICMP Time Exceeded (intermediate hop) or listen on the TCP socket for SYN-ACK / RST (destination reached)
6. Return structured result: `(hopIp: String, rttMs: Float, reached: Boolean)`
7. If destination reached (SYN-ACK received), send RST to close cleanly — do not complete the handshake

All three functions require `CAP_NET_RAW` capability.

## Kotlin JNI Interface

```kotlin
object SlogrNative {
    init {
        val libDir = System.getProperty("slogr.native.dir")
            ?: System.getenv("SLOGR_NATIVE_DIR")
            ?: "/opt/slogr/lib"
        System.load("$libDir/libslogr-native.so")
    }

    // TWAMP UDP
    external fun openSocket(ipv6: Boolean): Int
    external fun closeSocket(fd: Int)
    external fun sendPacket(fd: Int, destIp: String, destPort: Int, data: ByteArray): Int
    external fun recvPacket(fd: Int, buffer: ByteArray, timeoutMs: Int): RecvResult
    external fun setSocketTos(fd: Int, tos: Int)
    external fun setReceiveTtl(fd: Int, enable: Boolean)

    // Traceroute
    external fun icmpProbe(targetIp: String, ttl: Int, timeoutMs: Int): ProbeResult
    external fun udpProbe(targetIp: String, destPort: Int, ttl: Int, timeoutMs: Int): ProbeResult
    external fun tcpProbe(targetIp: String, destPort: Int, ttl: Int, timeoutMs: Int): ProbeResult
}

data class RecvResult(
    val bytesRead: Int,
    val data: ByteArray,
    val ttl: Int,
    val tos: Int,
    val srcIp: String,
    val srcPort: Int,
    val timestampNs: Long
)

data class ProbeResult(
    val hopIp: String?,           // null = timeout
    val rttMs: Float,
    val reached: Boolean,         // true = destination reached (not intermediate hop)
    val icmpType: Int,
    val icmpCode: Int
)
```

## Build Matrix

| Platform | Arch | Output | Priority |
|----------|------|--------|----------|
| Linux | amd64 | `libslogr-native.so` | R1 — required for AMI, Docker |
| Linux | arm64 | `libslogr-native.so` | R1 — required for ARM instances |
| macOS | amd64 | `libslogr-native.dylib` | R2 — CLI on developer Macs |
| macOS | arm64 (Apple Silicon) | `libslogr-native.dylib` | R2 |
| Windows | amd64 | `libslogr-native.dll` | R3 |

Build with: `gcc -shared -fPIC -o libslogr-native.so twampUdp.c traceroute.c -I$JAVA_HOME/include -I$JAVA_HOME/include/linux`

## Library Loading Strategy

1. Check `slogr.native.dir` system property
2. Check `SLOGR_NATIVE_DIR` environment variable
3. Default to platform-specific path:
   - Linux: `/opt/slogr/lib/`
   - macOS: `/usr/local/lib/slogr/` or `~/Library/Application Support/slogr/lib/`
   - Windows: `%APPDATA%\slogr\lib\`
4. If file not found at configured path, try extracting from JAR resources to configured directory
5. Never extract to `/tmp` (enterprise `noexec` mounts)
6. If all fail: **fall back to pure-Java mode** (see below). Log a warning. Do NOT exit.

## Pure-Java Fallback Mode

**This does NOT change the production architecture.** JNI remains the primary and only production transport. AMI, Docker, RPM, DEB packages always ship with the native library. The fallback exists solely for:
- Running on Windows during development (before `libslogr-native.dll` is compiled)
- Running on any platform where the native library hasn't been built yet
- Quick demos on a developer laptop without setting up a C build toolchain

### How it works

The engine uses a `UdpTransport` interface. Two implementations exist:

```kotlin
interface UdpTransport {
    fun openSocket(ipv6: Boolean): Int
    fun closeSocket(fd: Int)
    fun sendPacket(fd: Int, destIp: String, destPort: Int, data: ByteArray): Int
    fun recvPacket(fd: Int, buffer: ByteArray, timeoutMs: Int): RecvResult
    fun setSocketTos(fd: Int, tos: Int)
    fun setReceiveTtl(fd: Int, enable: Boolean)
    fun isNative(): Boolean
}

class NativeUdpTransport : UdpTransport {
    // Delegates to JNI SlogrNative.* — full TTL, DSCP, IPv6
    override fun isNative() = true
}

class JavaUdpTransport : UdpTransport {
    // Uses java.net.DatagramSocket
    // setSocketTos() and setReceiveTtl() are no-ops with a debug log
    override fun isNative() = false
}
```

Transport selection at startup:

```kotlin
val udpTransport: UdpTransport = try {
    SlogrNative.loadLibrary()
    NativeUdpTransport()
} catch (e: UnsatisfiedLinkError) {
    logger.warn("Native library not available — running in pure-Java fallback mode (no TTL capture, no DSCP)")
    JavaUdpTransport()
}
```

### What works in fallback mode

| Feature | JNI (production) | Java fallback |
|---------|-----------------|---------------|
| RTT measurement | ✅ nanosecond via recvmsg | ✅ nanosecond via System.nanoTime |
| Packet loss | ✅ | ✅ |
| Jitter | ✅ | ✅ |
| OOO detection | ✅ | ✅ |
| TTL capture | ✅ IP_RECVTTL | ❌ not available |
| DSCP/TOS | ✅ IP_TOS | ❌ not available |
| IPv6 hop limit | ✅ IPV6_RECVHOPLIMIT | ❌ not available |

### Traceroute in fallback mode

Same pattern — a `TracerouteTransport` interface with native and system fallback:

```kotlin
interface TracerouteTransport {
    suspend fun probe(targetIp: String, maxHops: Int, probesPerHop: Int, timeoutMs: Int, mode: TracerouteMode?): List<TracerouteHop>
    fun isNative(): Boolean
}

class NativeTracerouteTransport : TracerouteTransport {
    // JNI icmpProbe() / tcpProbe() / udpProbe() — structured results, no parsing
    override fun isNative() = true
}

class SystemTracerouteTransport : TracerouteTransport {
    // Wraps OS traceroute (Linux/macOS) or tracert (Windows) via ProcessBuilder
    // Detects platform, selects output parser
    // NOT shell-invoked — ProcessBuilder with argument list
    override fun isNative() = false
}
```

| Platform | System command | Notes |
|----------|---------------|-------|
| Linux | `traceroute` | Must be installed (`apt install traceroute`) |
| macOS | `/usr/sbin/traceroute` | Pre-installed |
| Windows | `tracert` | Built-in |

### Startup banner in fallback mode

```
⚠ Running in pure-Java fallback mode
  - TTL capture: disabled (requires native library)
  - DSCP/TOS: disabled (requires native library)
  - Traceroute: using system traceroute/tracert
  For full functionality, install the native library or use Docker/AMI.
```

### Architecture impact: NONE

The `MeasurementEngine` interface, `MeasurementResult` data class, all module boundaries, the report schema, OTLP metric names, RabbitMQ publisher, Pub/Sub subscriber, CLI interface — nothing changes. The fallback is invisible to everything above `UdpTransport` and `TracerouteTransport`. Fields that require JNI (TTL values) are nullable in `PacketEntry` and `TracerouteHop` — they're simply null in fallback mode.

## Windows Data Directories

When running on Windows (development/CLI use), default paths adapt:

| Purpose | Linux/macOS | Windows |
|---------|-------------|---------|
| Data dir (WAL, credentials, ASN DB) | `/opt/slogr/data/` | `%APPDATA%\slogr\data\` |
| Native lib dir | `/opt/slogr/lib/` | `%APPDATA%\slogr\lib\` |
| Log dir | journald / stdout | `%APPDATA%\slogr\logs\` |
| Config | `/opt/slogr/config/` | `%APPDATA%\slogr\config\` |

All overridable with `SLOGR_DATA_DIR`, `SLOGR_NATIVE_DIR` env vars or system properties.

## Running on Windows (Development Quick Start)

```powershell
# Prerequisites: JDK 21 installed
# Download the fat JAR (no native library needed for fallback mode)
# From the project root after building:

java -jar build/slogr-agent.jar version
java -jar build/slogr-agent.jar check 10.0.1.5 --profile voip
java -jar build/slogr-agent.jar check 10.0.1.5 --traceroute --format json
```

No native library compilation needed. No installer needed. Just the JAR and a JDK.
