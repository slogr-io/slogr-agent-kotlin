---
status: locked
version: 1.0
depends-on:
  - architecture/data-model
  - modules/jni-native
claude-code-context:
  - "Read when implementing the TWAMP engine"
  - "Implement based on RFC 5357 spec (reference Java agent for protocol understanding)"
---

# TWAMP Engine

## Overview

Full RFC 5357 Two-Way Active Measurement Protocol implementation. Clean-room Kotlin implementation based on the RFC specification. The Java agent's architecture was studied for protocol understanding only. The agent acts as both controller (sender, initiates tests) and responder (reflector, responds to other controllers including third-party routers).

## Session Types

| Type | Controller | Reflector | Use Case |
|------|-----------|-----------|----------|
| Agent → Agent | This agent | Another Slogr agent | Mesh measurements |
| Agent → Router | This agent | Cisco/Juniper/Nokia/any RFC 5357 device | Infrastructure monitoring |
| Router → Agent | External device | This agent's reflector | Customer uses their existing TWAMP controllers |

## Implementation Guide — Key Components

| Java Class | Kotlin Equivalent | Notes |
|------------|------------------|-------|
| `TwampClient.java` | `TwampController.kt` | NIO Selector for TCP control. Single thread, non-blocking. |
| `TwampControlSession.java` | `TwampControlSession.kt` | Per-connection state machine. |
| `TwampSessionSender.java` | `TwampSessionSender.kt` | UDP packet sender. One thread per active session. Time-critical. |
| `TwampServer.java` | `TwampReflector.kt` | TCP control listener for incoming sessions. |
| `TwampSessionReflector.java` | `TwampSessionReflector.kt` | UDP packet reflector. |
| `SessionScheduler.java` | Replaced by `platform/scheduler/` | Scheduling moves to the platform layer. |
| `ReportScheduler.java` | Replaced by `ResultPublisher` interface | Reporting moves to the platform layer. |
| `ConfigManager.java` | Replaced by CLI + Pub/Sub config | Config comes from CLI args or commands. |
| `TwampWhiteList.java` | `IpWhitelist.kt` | Thread-safe IP allowlist for the reflector. |
| `TwampMetricsData.java` | `MeasurementResult` data class | Already defined in contracts. |

## Authentication Modes

The Java agent has **full authenticated mode implementation**. Claude Code should implement this from the RFC specification, fixing the known interop issues listed below.

| Mode | Java Status | Kotlin R1 |
|------|------------|-----------|
| Unauthenticated | Fully implemented, RFC compliant | Implement |
| Authenticated | Fully implemented — PBKDF2 key derivation, AES-CBC token, HMAC-SHA1 truncated to 128 bits, per-message CBC chaining, per-session test key derivation | Implement |
| Encrypted | Fully implemented — AES-CBC first 32/96 bytes, HMAC over plaintext | Defer to R2 |

Key Java classes to reference for auth mode understanding:
- `KeyStore.java` — PBKDF2WithHmacSHA1 key derivation, AES-CBC/ECB encrypt/decrypt
- `TwampControlSessionKey.java` — session key management
- `KeyChain.java` — key ID → secret mapping
- `Mode.java` — mode negotiation, `getTestSessionMode()` for per-session key derivation

```kotlin
data class TwampKeyConfig(
    val keyId: String,
    val secret: ByteArray,                    // shared secret for PBKDF2
    val algorithm: String = "HmacSHA1"        // HMAC-SHA1 truncated to 128 bits (RFC + Cisco/Juniper standard)
)
```

## RFC Extensions to Implement

| Extension | RFC | What it does |
|-----------|-----|-------------|
| Individual Session Control (StartN/StopN) | RFC 5938 | Start/stop individual sessions, not just all-at-once |
| Reflect Octets | RFC 6038 | Reflector returns requested padding bytes |
| Symmetrical Size | RFC 6038 | Request and response packets same size |
| IPv6 test sessions | RFC 5357 | Full IPv6 path in JNI and control session |

## Interop Issues to Address

These are bugs in the Java code that will cause failures against some third-party TWAMP devices. Fix them in the Kotlin implementation.

### FIX-1: Count ceiling rejects valid servers (CRITICAL for router interop)

Java rejects ServerGreeting if `count > maxCount` (default 32768). Cisco IOS commonly sends `count=65536`. RFC places no upper bound on count.

**Fix:** Remove the upper bound check. Accept any count that is a power of 2 and >= 1024 (per RFC). Or make maxCount configurable with a much higher default (1048576).

### FIX-2: StopSessions count must match exactly (CRITICAL for router interop)

Java responder closes TCP connection if `stopSessions.sessionNo != activeSessions.size()`. Many TWAMP controllers send `sessionNo=0` in StopSessions. RFC says the count is informational.

**Fix:** Treat `sessionNo` as informational. On StopSessions, stop all active sessions regardless of the count field. Log a warning if the count doesn't match but don't disconnect.

### FIX-3: encIV flag breaks the RFC

A command-line toggle (`encIV`) encrypts the Client-IV before sending. RFC 4656 §3.1 says Client-IV MUST be sent unencrypted.

**Fix:** Remove the `encIV` toggle entirely. Always send Client-IV unencrypted.

## Known Java Bugs to Avoid

These are correctness bugs that don't affect normal operation but should be fixed.

### BUG-A: ReflectorUPacket reads mbz2 into mbz1
`bb.get(this.mbz1)` called twice instead of `bb.get(this.mbz2)` the second time. Fix: use correct field.

### BUG-B: ReflectorEncryptUPacket setters assign field to itself
`setMbz2(byte[] mbz1) { this.mbz2 = mbz2; }` — parameter named `mbz1` but assigns `mbz2` to itself. Fix: `this.mbz2 = mbz1` (or rename parameter).

### BUG-C: Mixed mode Server-IV not generated
`genServerStart()` only generates random IV for modes 2 and 4, not mode 8 (mixed). Fix: generate random IV for any mode where `isControlEncrypted()` returns true.

### BUG-D: Test session IV aliasing
`testMode.sendIV = testMode.receiveIV` — both point to same array. Currently latent (updateIV=false in test packet CBC). Fix: allocate separate arrays.

### BUG-E: Wrong comment in SenderEncryptUPacket
Comment says "Authenticated mode" but the code block is actually encrypted mode. Fix: correct the comment.

## Packet Timing

| Mode | Implementation | Source |
|------|---------------|--------|
| Fixed interval | `scheduleAtFixedRate(interval)` with optional random `delayedStart` in `[0, interval)` | Java `TwampSessionSender` |
| Poisson | `TimeUtil.getPoissonRandom(lambda)` — exponential distribution, capped at `maxInterval` | Java `TimeUtil` |

## Per-Packet Statistics

Every sent/received packet produces a `PacketEntry`:
- Sequence number
- TX/RX timestamps (nanosecond precision from TWAMP)
- Reflector processing time
- Forward and reverse one-way delay
- Forward and reverse IPDV (jitter)
- TX and RX TTL (via JNI `IP_RECVTTL`)
- Out-of-order flag

Aggregate statistics (`MeasurementResult` min/avg/max/jitter/loss) are computed from the packet entries.

## skipCycles Optimization

From the Java agent: every N-th cycle sends full per-packet data. Other cycles send summary only. This reduces RabbitMQ message size for high-frequency testing.

```kotlin
data class SkipCyclesConfig(
    val interval: Int = 0,                    // 0 = send full data every time
    val currentCycle: AtomicInteger = AtomicInteger(0)
) {
    fun shouldSendFullData(): Boolean = interval == 0 || currentCycle.getAndIncrement() % interval == 0
}
```

## Error Handling

- TCP control connection refused → log, report failure, retry with next scheduled run
- TCP control timeout (60s) → close session, log, report failure
- UDP packet loss → tracked in statistics, not an error
- All reflector sessions auto-expire after configured max duration (default 300s)
- Failed sessions produce a `MeasurementResult` with `grade = null` and error metadata 

## Slogr Agent Detection via ServerGreeting

RFC 5357 ServerGreeting begins with 12 "unused" bytes. Slogr agents embed a fingerprint here:

```
Bytes 0-4:   0x534C4F4752 ("SLOGR" ASCII magic)
Bytes 5-6:   protocol version (uint16 big-endian, 0x0001 for R1)
Bytes 7-12:  first 6 bytes of the responder's agent_id UUID
```

**Controller behavior on connect:**

1. Read ServerGreeting from target
2. Check bytes 0-4 for "SLOGR" magic
3. If present: target is a Slogr agent. Extract agent_id hint (bytes 7-12). In connected mode, resolve full `dest_agent_id` from the backend schedule or agent registry. In CLI mode, include the partial ID in report metadata.
4. If not present: target is a third-party device (Cisco, Juniper, etc.). Set `dest_agent_id = UUID5(NAMESPACE_DNS, "device:{target_ip}:{target_port}")` — deterministic, consistent across restarts, unique per target.

**Responder behavior:**

The Slogr agent's TWAMP responder always writes the fingerprint into the ServerGreeting's unused bytes. This does not break RFC compliance — the bytes are defined as unused, and all compliant controllers ignore them.

```kotlin
fun buildServerGreeting(agentId: UUID): ByteArray {
    val greeting = ByteArray(64)
    // Bytes 0-4: SLOGR magic
    "SLOGR".toByteArray(Charsets.US_ASCII).copyInto(greeting, 0)
    // Bytes 5-6: protocol version
    greeting[5] = 0x00; greeting[6] = 0x01
    // Bytes 7-12: first 6 bytes of agent_id
    val idBytes = agentId.toByteArray()
    idBytes.copyInto(greeting, 7, 0, 6)
    // Bytes 12-15: modes (standard RFC field)
    // ... rest of ServerGreeting per RFC 5357
    return greeting
}
```

Third-party routers (Cisco, Juniper, Nokia) set the unused bytes to zero or random values. The "SLOGR" magic is a 5-byte ASCII string — the probability of a false positive is negligible.
