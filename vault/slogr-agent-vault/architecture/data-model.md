---
status: locked
version: 1.0
depends-on:
  - architecture/system-overview
claude-code-context:
  - "Read before writing any Kotlin code"
  - "These are the shared contracts all modules depend on"
---

# Data Model

## Core Principle

All modules communicate through these data classes and interfaces. No module reaches into another module's internals. If a module needs data, it receives it through a defined interface or data class.

## Domain Objects

### MeasurementResult

The output of a single TWAMP test session. This is the primary data object the entire agent revolves around.

```kotlin
data class MeasurementResult(
    val sessionId: UUID,
    val pathId: UUID,
    val sourceAgentId: UUID,
    val destAgentId: UUID,                    // Real UUID if target is Slogr agent (detected via ServerGreeting fingerprint).
                                              // Deterministic UUID5(NAMESPACE_DNS, "device:{ip}:{port}") if target is a third-party router/switch.
    val sourceType: String = "agent",       // future: "chrome", "homeassistant"
    val srcCloud: String,                    // "aws", "gcp", "azure"
    val srcRegion: String,                   // "us-east-1"
    val dstCloud: String,
    val dstRegion: String,
    val windowTs: Instant,                   // 5-min window timestamp
    val profile: SlaProfile,
    // Ground-truth RTT: (T4-T1) - (T3-T2), always clock-independent
    val rttMinMs: Float,
    val rttAvgMs: Float,
    val rttMaxMs: Float,
    // Forward (sender → reflector) — ratio-scaled portion of RTT
    val fwdMinRttMs: Float,
    val fwdAvgRttMs: Float,
    val fwdMaxRttMs: Float,
    val fwdJitterMs: Float,
    val fwdLossPct: Float,
    // Reverse (reflector → sender) — remainder: rtt - fwd. 0 if UNSYNCABLE.
    val revMinRttMs: Float?,
    val revAvgRttMs: Float?,
    val revMaxRttMs: Float?,
    val revJitterMs: Float?,
    val revLossPct: Float?,
    // Packet stats
    val packetsSent: Int,
    val packetsRecv: Int,
    // Per-packet data (from Java heritage)
    val packets: List<PacketEntry>? = null,  // null when skipCycles is active
    // SLA evaluation
    val grade: SlaGrade? = null,
    val schemaVersion: Int = 1
)

data class PacketEntry(
    val seq: Int,
    val txTimestamp: Instant,
    val rxTimestamp: Instant?,
    val reflectorProcTimeNs: Long?,
    val fwdDelayMs: Float?,
    val revDelayMs: Float?,
    val fwdJitterMs: Float?,
    val revJitterMs: Float?,
    val txTtl: Int?,
    val rxTtl: Int?,
    val outOfOrder: Boolean = false
)
```

### TracerouteResult

The output of a traceroute run. Matches the `traceroute_raw` ClickHouse schema.

```kotlin
data class TracerouteResult(
    val sessionId: UUID,
    val pathId: UUID,
    val sourceType: String = "agent",
    val direction: Direction,                // UPLINK or DOWNLINK
    val capturedAt: Instant,
    val isHeartbeat: Boolean = false,
    val isForcedRefresh: Boolean = false,
    val hops: List<TracerouteHop>,
    val prevSnapshotId: UUID? = null,
    val changedHops: List<Int> = emptyList(),
    val primaryAsnChange: Int? = null,
    val schemaVersion: Int = 1
)

data class TracerouteHop(
    val ttl: Int,
    val ip: InetAddress?,                    // null = timeout (*)
    val asn: Int? = null,
    val asnName: String? = null,
    val rttMs: Float?,
    val lossPct: Float?
)

enum class Direction { UPLINK, DOWNLINK }
```

### AsnPath

Deduplicated ordered ASN path extracted from traceroute hops.

```kotlin
data class AsnPath(
    val asns: List<Int>,                     // deduplicated, ordered
    val sessionId: UUID,
    val capturedAt: Instant
)
```

### PathChangeEvent

Emitted when the ASN path changes.

```kotlin
data class PathChangeEvent(
    val pathId: UUID,
    val direction: Direction,
    val prevAsnPath: List<Int>,
    val newAsnPath: List<Int>,
    val primaryChangedAsn: Int,
    val primaryChangedAsnName: String,
    val changedHopTtl: Int,
    val hopDeltaMs: Float
)
```

### SlaProfile and SlaGrade

```kotlin
data class SlaProfile(
    val name: String,                        // "voip", "gaming", "streaming"
    val nPackets: Int,
    val intervalMs: Long,                    // milliseconds between packets
    val waitTimeMs: Long,                    // timeout per packet
    val dscp: Int,                           // 0-63
    val packetSize: Int,                     // bytes
    val timingMode: TimingMode = TimingMode.FIXED,
    val poissonLambda: Double? = null,
    val poissonMaxInterval: Long? = null,
    // Thresholds (green = good, red = bad)
    val rttGreenMs: Float,
    val rttRedMs: Float,
    val jitterGreenMs: Float,
    val jitterRedMs: Float,
    val lossGreenPct: Float,
    val lossRedPct: Float
)

enum class TimingMode { FIXED, POISSON }

enum class SlaGrade { GREEN, YELLOW, RED }
```

### HealthSnapshot

Matches the `agent_health` ClickHouse schema.

```kotlin
data class HealthSnapshot(
    val agentId: UUID,
    val tenantId: UUID,
    val sourceType: String = "agent",
    val reportedAt: Instant,
    val lastTwampSuccessAt: Instant?,
    val lastTracerouteSuccessAt: Instant?,
    val publishStatus: PublishStatus,
    val bufferSizeRows: Int,
    val bufferOldestTs: Instant?,
    val twampFailureCount: Int,
    val tracerouteFailureCount: Int,
    val publishFailureCount: Int,
    val workerRestartCount: Int,
    val agentRestartCount: Int,
    val schemaVersion: Int = 1
)

enum class PublishStatus { OK, DEGRADED, FAILING }
```

### AgentCredential

Stored locally after registration or `slogr connect`.

```kotlin
data class AgentCredential(
    val agentId: UUID,
    val tenantId: UUID,
    val displayName: String,
    val jwt: String,                         // long-lived signed JWT
    val rabbitmqJwt: String,                 // short-lived RabbitMQ JWT (refreshable)
    val rabbitmqHost: String,
    val rabbitmqPort: Int,
    val pubsubSubscription: String,          // slogr.agent-commands.{agent_id}
    val issuedAt: Instant,
    val connectedVia: ConnectionMethod       // BOOTSTRAP_TOKEN or API_KEY
)

enum class ConnectionMethod { BOOTSTRAP_TOKEN, API_KEY }
```

### SessionConfig

What the scheduler works with. Received via `set_schedule` command or loaded from local config.

```kotlin
data class SessionConfig(
    val pathId: UUID,
    val targetIp: InetAddress,
    val targetPort: Int = 862,
    val profile: SlaProfile,
    val intervalSeconds: Int = 300,          // how often to run (default 5 min)
    val tracerouteEnabled: Boolean = true,
    val skipCycles: Int = 0                  // 0 = send full per-packet data every time; N = full data every N-th cycle, summary only otherwise
)

data class Schedule(
    val sessions: List<SessionConfig>,
    val receivedAt: Instant,
    val commandId: UUID? = null              // if received via Pub/Sub command
)
```

## Core Interfaces

### MeasurementEngine

The interface between the measurement core and the platform shell. The platform calls this to run tests. The implementation handles TWAMP, traceroute, ASN, and SLA evaluation internally.

```kotlin
interface MeasurementEngine {
    /** Run a complete measurement: TWAMP + optional traceroute + ASN + SLA eval.
     *  Target can be another Slogr agent or any RFC 5357 compliant TWAMP reflector (router/switch). */
    suspend fun measure(
        target: InetAddress,
        targetPort: Int = 862,
        profile: SlaProfile,
        traceroute: Boolean = true,
        authMode: TwampAuthMode = TwampAuthMode.UNAUTHENTICATED,
        keyId: String? = null
    ): MeasurementBundle

    /** Run TWAMP only */
    suspend fun twamp(
        target: InetAddress,
        targetPort: Int = 862,
        profile: SlaProfile
    ): MeasurementResult

    /** Run traceroute only */
    suspend fun traceroute(
        target: InetAddress,
        maxHops: Int = 30,
        probesPerHop: Int = 2,
        timeoutMs: Int = 2000,
        mode: TracerouteMode? = null         // null = auto (try ICMP → TCP/443 → UDP)
    ): TracerouteResult

    /** Shutdown: cancel in-flight tests, release resources */
    fun shutdown()
}

/** Bundle of all outputs from a single measurement cycle */
data class MeasurementBundle(
    val twamp: MeasurementResult,
    val traceroute: TracerouteResult?,
    val pathChange: PathChangeEvent?,
    val grade: SlaGrade
)

enum class TracerouteMode { ICMP, UDP, TCP }

enum class TwampAuthMode { UNAUTHENTICATED, AUTHENTICATED, ENCRYPTED }

/** Describes the target of a TWAMP session — either another Slogr agent or a third-party device */
data class TwampTarget(
    val ip: InetAddress,
    val port: Int = 862,
    val agentId: UUID? = null,               // null if target is a router/switch
    val deviceType: TargetDeviceType = TargetDeviceType.SLOGR_AGENT,
    val authMode: TwampAuthMode = TwampAuthMode.UNAUTHENTICATED,
    val keyId: String? = null                // for authenticated/encrypted modes
)

enum class TargetDeviceType { SLOGR_AGENT, CISCO, JUNIPER, GENERIC_RFC5357 }
```

### ResultPublisher

The interface the platform shell implements for delivering results.

```kotlin
interface ResultPublisher {
    /** Publish a measurement result. Returns true if acknowledged. */
    suspend fun publishMeasurement(result: MeasurementResult): Boolean

    /** Publish a traceroute snapshot. */
    suspend fun publishTraceroute(result: TracerouteResult): Boolean

    /** Publish a health snapshot. */
    suspend fun publishHealth(snapshot: HealthSnapshot): Boolean

    /** Flush any buffered data. Called on shutdown. */
    suspend fun flush()
}
```

### CredentialStore

Local credential persistence.

```kotlin
interface CredentialStore {
    fun load(): AgentCredential?
    fun store(credential: AgentCredential)
    fun delete()
    fun isConnected(): Boolean
}
```

## OTLP Metric Names

These are the OpenTelemetry metric names the agent emits. Locked — changing these after release breaks compatibility with the Slogr Proxy.

The `slogr.network.*` namespace is intentionally generic — not `slogr.twamp.*`. This ensures all measurement sources (TWAMP agents, WebRTC browser probes, mobile SDK) emit the same metric names. The `source_type` attribute distinguishes the source. Any OTel-compatible collector, Grafana dashboard, or alerting rule works without knowing how the measurement was taken.

```
# Network measurement metrics (source-agnostic)
slogr.network.rtt.min               gauge, unit: ms  (ground-truth RTT, clock-independent)
slogr.network.rtt.avg               gauge, unit: ms  (ground-truth RTT, clock-independent)
slogr.network.rtt.max               gauge, unit: ms  (ground-truth RTT, clock-independent)
slogr.network.rtt.forward.min       gauge, unit: ms  (directional split, ratio-scaled from RTT)
slogr.network.rtt.forward.avg       gauge, unit: ms
slogr.network.rtt.forward.max       gauge, unit: ms
slogr.network.rtt.reverse.min       gauge, unit: ms
slogr.network.rtt.reverse.avg       gauge, unit: ms
slogr.network.rtt.reverse.max       gauge, unit: ms
slogr.network.jitter.forward        gauge, unit: ms
slogr.network.jitter.reverse        gauge, unit: ms
slogr.network.loss.forward           gauge, unit: percent (0-100)
slogr.network.loss.reverse           gauge, unit: percent (0-100)
slogr.network.packets.sent           gauge, unit: count
slogr.network.packets.received       gauge, unit: count

# Traceroute metrics
slogr.network.traceroute.hop_count   gauge, unit: count
slogr.network.traceroute.path_changed gauge, unit: boolean (0 or 1)

# SLA metrics
slogr.network.sla.grade              gauge, unit: enum (0=green, 1=yellow, 2=red)

# Agent health metrics (agent-specific, not network)
slogr.agent.buffer.size              gauge, unit: count
slogr.agent.failures.twamp           counter
slogr.agent.failures.traceroute      counter
slogr.agent.failures.publish         counter
```

**Common attributes on all metrics:**
```
agent_id:        UUID string
session_id:      UUID string
path_id:         UUID string
profile:         string ("voip", "gaming", etc.)
src_cloud:       string
src_region:      string
dst_cloud:       string
dst_region:      string
source_type:     string ("agent", "browser", "home_assistant")
measurement_method: string ("twamp", "webrtc", "icmp")   ← how the measurement was taken
```
