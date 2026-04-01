# Operational Hardening

**Status:** Locked
**New in R2 — sourced from Gemini architecture review**

---

## 1. JNI SO_TIMESTAMPING Fallback

### Problem

Not all cloud hypervisors, VPCs, or older Linux kernels correctly pass kernel timestamps via `recvmsg()`. Azure VMs, older AWS Nitro instances, and VMware guests may return empty `CMSG_DATA`.

### Rule

If `CMSG_DATA` is empty or `SO_TIMESTAMPING` is not supported, fall back to userspace `clock_gettime(CLOCK_REALTIME)`. Never drop the packet. Never crash.

### Implementation

In `twampUdp.c`:

```c
// After recvmsg():
if (cmsg == NULL || cmsg->cmsg_level != SOL_SOCKET) {
    // Kernel timestamp unavailable — fallback to userspace
    clock_gettime(CLOCK_REALTIME, &ts);
    result.timestamp_source = TIMESTAMP_USERSPACE;  // = 2
} else {
    // Kernel timestamp available
    memcpy(&ts, CMSG_DATA(cmsg), sizeof(struct timespec));
    result.timestamp_source = TIMESTAMP_KERNEL;  // = 1
}
```

### Contract

Add `timestamp_source` to the JNI return struct. Propagate to Kotlin:

```kotlin
enum class TimestampSource {
    KERNEL,      // SO_TIMESTAMPING — microsecond precision
    USERSPACE    // clock_gettime — millisecond precision, ±1ms OS scheduler jitter
}
```

The `VirtualClockEstimator` must account for this: if `timestamp_source == USERSPACE`, widen the sync detection tolerance. The `ClockSyncDetector` should never report `SYNCED` when timestamps are userspace — only `ESTIMATED` or `UNSYNCABLE`.

### Files

| File | Action |
|------|--------|
| `native/src/twampUdp.c` | MODIFY — add fallback, add `timestamp_source` to return struct |
| `native/src/slogr_native.h` | MODIFY — add `timestamp_source` field to JNI struct |
| `engine/twamp/TwampPacketResult.kt` | MODIFY — add `timestampSource` field |
| `engine/clock/ClockSyncDetector.kt` | MODIFY — never report SYNCED when source is USERSPACE |

---

## 2. File-Backed Persistent Fingerprint

### Problem

`SHA256(mac + hostname)` computed dynamically on every boot fails in:
- K8s DaemonSets — all pods on same node share MAC
- VMware clones — cloned VMs have identical MACs until reconfigured
- VDI pools — ephemeral sessions with recycled MACs
- Docker — container MACs are assigned randomly per run

### Rule

On first boot, generate the fingerprint and write it to a persistent file. On all subsequent boots, read the file first. Only regenerate if the file doesn't exist.

### Implementation

```kotlin
// PersistentFingerprint.kt
object PersistentFingerprint {
    private val FINGERPRINT_FILE = when {
        System.getProperty("os.name").lowercase().contains("win") ->
            Path.of(System.getenv("ProgramData"), "Slogr", ".agent_fingerprint")
        else ->
            Path.of("/var/lib/slogr/.agent_fingerprint")
    }

    fun get(): String {
        // Read existing fingerprint
        if (Files.exists(FINGERPRINT_FILE)) {
            return Files.readString(FINGERPRINT_FILE).trim()
        }

        // Generate new fingerprint
        val mac = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.hardwareAddress != null && !it.isLoopback }
            .firstOrNull()?.hardwareAddress?.let { Hex.encodeHexString(it) } ?: UUID.randomUUID().toString()
        val hostname = InetAddress.getLocalHost().hostName
        val raw = "$mac:$hostname:${UUID.randomUUID()}"  // UUID adds uniqueness for clones
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .let { Hex.encodeHexString(it) }

        // Persist
        Files.createDirectories(FINGERPRINT_FILE.parent)
        Files.writeString(FINGERPRINT_FILE, fingerprint)
        return fingerprint
    }
}
```

Key: the `UUID.randomUUID()` ensures that even if two cloned VMs have identical MAC+hostname, their fingerprints will diverge after their first independent boot.

### Files

| File | Action |
|------|--------|
| `platform/identity/PersistentFingerprint.kt` | NEW — replaces dynamic `MachineIdentity.fingerprint()` |
| `platform/credential/MachineIdentity.kt` | MODIFY — delegate fingerprint to `PersistentFingerprint.get()` |

---

## 3. Bounded WAL Eviction

### Problem

When JWT refresh fails or network is lost for days, the agent buffers to WAL. Without a size limit, WAL fills the host's disk. An observability agent crashing a production server is a catastrophic failure.

### Rule

WAL has a hard cap. When exceeded, silently drop the oldest entries. Never fill the disk.

### Implementation

```kotlin
// In WriteAheadLog.kt
const val WAL_MAX_SIZE_BYTES = 500 * 1024 * 1024L  // 500 MB
const val WAL_MAX_AGE_HOURS = 72L                    // 3 days

fun append(entry: WalEntry) {
    evictIfNeeded()
    // ... write entry
}

private fun evictIfNeeded() {
    val walDir = walPath.toFile()
    val files = walDir.listFiles()?.sortedBy { it.lastModified() } ?: return

    // Evict by age
    val cutoff = Instant.now().minus(WAL_MAX_AGE_HOURS, ChronoUnit.HOURS)
    files.filter { Instant.ofEpochMilli(it.lastModified()).isBefore(cutoff) }
        .forEach { it.delete(); logger.info("WAL evicted (age): ${it.name}") }

    // Evict by size (oldest first)
    var totalSize = walDir.listFiles()?.sumOf { it.length() } ?: 0
    val remainingFiles = walDir.listFiles()?.sortedBy { it.lastModified() } ?: return
    for (file in remainingFiles) {
        if (totalSize <= WAL_MAX_SIZE_BYTES) break
        totalSize -= file.length()
        file.delete()
        logger.info("WAL evicted (size): ${file.name}")
    }
}
```

Configurable via `push_config`:

```json
{
  "wal_max_size_mb": 500,
  "wal_max_age_hours": 72
}
```

### Files

| File | Action |
|------|--------|
| `platform/buffer/WriteAheadLog.kt` | MODIFY — add eviction logic |
| `platform/config/AgentConfig.kt` | MODIFY — add `wal_max_size_mb`, `wal_max_age_hours` config |
| `platform/commands/PushConfigHandler.kt` | MODIFY — add WAL config fields |

---

## 4. ICMP/TCP Measurement Interpretation

### Problem

Modern firewalls frequently drop ICMP but allow TCP :443. If ICMP shows 100% loss but TCP connect succeeds, the path is healthy — just ICMP-filtered. Displaying "100% packet loss" is misleading.

### Rule

When `icmp_loss_pct = 100%` AND `tcp_success = true`, the measurement is "TCP-only" — not "total loss." The agent must flag this explicitly.

### Implementation

Add `probe_mode` to `probe_raw` schema:

```kotlin
enum class ProbeMode {
    ICMP_AND_TCP,   // Both worked — full data
    TCP_ONLY,       // ICMP blocked, TCP succeeded — path is up, ICMP filtered
    ICMP_ONLY,      // TCP failed, ICMP worked — unusual but possible
    BOTH_FAILED     // Neither worked — target may be down
}

// In IcmpPingProbe result assembly:
val probeMode = when {
    icmpSuccess && tcpSuccess -> ICMP_AND_TCP
    !icmpSuccess && tcpSuccess -> TCP_ONLY
    icmpSuccess && !tcpSuccess -> ICMP_ONLY
    else -> BOTH_FAILED
}
```

The `probe_mode` is included in the `probe_raw` JSON and ClickHouse table so the SaaS Explanation Engine knows not to trigger a "100% loss" alert when `probe_mode = TCP_ONLY`.

### Files

| File | Action |
|------|--------|
| `contracts/src/.../ProbeResult.kt` | MODIFY — add `probeMode` field |
| `engine/probe/IcmpPingProbe.kt` | MODIFY — compute probe mode |
| `platform/output/TextResultFormatter.kt` | MODIFY — display "ICMP filtered (TCP healthy)" instead of "100% loss" |
| `platform/output/JsonResultFormatter.kt` | MODIFY — include `probe_mode` in JSON output |

---

## 5. Local Prometheus Exporter

### Problem

SREs want immediate local scraping via Prometheus before configuring OTLP pipelines. Waiting for OTLP setup creates adoption friction.

### Rule

Embedded HTTP server on `127.0.0.1:9090/metrics` serving Prometheus exposition format. Always available. NOT gated behind `sk_free_*` — this is a local-only feature.

### Implementation

```kotlin
// PrometheusExporter.kt
class PrometheusExporter(private val port: Int = 9090) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    fun start() {
        server.createContext("/metrics") { exchange ->
            val metrics = buildPrometheusMetrics()
            exchange.sendResponseHeaders(200, metrics.length.toLong())
            exchange.responseBody.write(metrics.toByteArray())
            exchange.responseBody.close()
        }
        server.executor = null
        server.start()
        logger.info("Prometheus metrics available at http://127.0.0.1:$port/metrics")
    }

    private fun buildPrometheusMetrics(): String = buildString {
        // Agent health metrics
        appendLine("# HELP slogr_agent_state Agent state (0=anonymous, 1=registered, 2=connected)")
        appendLine("# TYPE slogr_agent_state gauge")
        appendLine("slogr_agent_state ${agentState.ordinal}")

        appendLine("# HELP slogr_agent_uptime_seconds Agent uptime in seconds")
        appendLine("# TYPE slogr_agent_uptime_seconds gauge")
        appendLine("slogr_agent_uptime_seconds ${uptimeSeconds()}")

        appendLine("# HELP slogr_active_sessions Number of active measurement sessions")
        appendLine("# TYPE slogr_active_sessions gauge")
        appendLine("slogr_active_sessions $activeSessions")

        // Per-session metrics (last measurement)
        // slogr_rtt_ms{target="10.0.1.5", method="twamp"} 14.3
        // slogr_loss_pct{target="10.0.1.5", method="twamp"} 0.0
        // slogr_jitter_ms{target="10.0.1.5", method="twamp"} 2.1
    }
}
```

Binds to `127.0.0.1` only — never `0.0.0.0`. Local scraping only. No authentication needed because it's localhost.

Starts automatically in daemon mode. Does NOT start for one-shot `check` commands.

### Files

| File | Action |
|------|--------|
| `platform/health/PrometheusExporter.kt` | NEW |
| `platform/cli/DaemonCommand.kt` | MODIFY — start PrometheusExporter on daemon start |

---

## 6. Doctor Command

### Problem

"It doesn't work" is the most common support ticket. A self-diagnostic command eliminates most of them.

### Implementation

```bash
$ slogr-agent doctor

Slogr Agent Diagnostics
=======================
JNI native library:     ✓ loaded (libslogr_native.so)
CAP_NET_RAW:            ✓ available
CAP_NET_BIND_SERVICE:   ✓ available
ICMP ping (localhost):  ✓ working
DNS resolution:         ✓ slogr.io resolves to 104.18.12.33
TLS connectivity:       ✓ slogr.io:443 reachable (TLS 1.3)
API key:                ✓ SLOGR_API_KEY set (sk_live_...c3d4)
Key validation:         ✓ valid (tenant: acme-corp)
RabbitMQ:               ✓ mq.slogr.io:5671 reachable
Pub/Sub:                ✓ subscription active
Agent state:            CONNECTED
Disk space:             ✓ /var/lib/slogr/ has 12.4 GB free
WAL:                    ✓ 0 entries pending, 0 MB used

All checks passed.
```

Failed check example:

```
CAP_NET_RAW:            ✗ NOT available
  → Run: sudo setcap cap_net_raw+ep /usr/bin/slogr-agent
  → Or run as root
```

Each failed check includes a specific remediation instruction.

### Files

| File | Action |
|------|--------|
| `platform/cli/DoctorCommand.kt` | NEW |
| `platform/cli/SlogrCli.kt` | MODIFY — register `doctor` subcommand |

---

## 7. Kill Switch (HaltMeasurement Command)

### Problem

If an agent accidentally floods a target (misconfigured schedule, bug in schedule push), Slogr ops needs an instant remote kill switch.

### Rule

New command type: `halt_measurement`. When received, the agent immediately stops all active measurement sessions, purges the in-memory schedule, and enters an idle connected state. It does NOT deregister — it stays connected and responsive to future commands.

### Payload

```json
{
  "command_type": "halt_measurement",
  "payload": {
    "reason": "emergency_stop" | "target_overload" | "ops_manual"
  }
}
```

### Agent Behavior

1. Receive `halt_measurement` via Pub/Sub
2. Immediately stop all TWAMP sessions (close sockets)
3. Immediately stop all ICMP/TCP probe sessions
4. Immediately stop all traceroute runs
5. Purge in-memory schedule (but do NOT delete persisted schedule file — ops may want to resume)
6. ACK the command with status `acked`
7. Agent remains connected. Health signals continue. Commands accepted.
8. To resume: ops sends a new `set_schedule` command

### Timeout

30 seconds. If not ACKed within 30 seconds, status → `timed_out`.

### Files

| File | Action |
|------|--------|
| `platform/commands/HaltMeasurementHandler.kt` | NEW |
| `platform/commands/CommandDispatcher.kt` | MODIFY — register `halt_measurement` command type |
| Layer 2.5 vault: `command-payloads.md` | UPDATE — add `halt_measurement` as 6th command type |
| Layer 2.5 vault: `command-lifecycle.md` | UPDATE — add 30s timeout for `halt_measurement` |
