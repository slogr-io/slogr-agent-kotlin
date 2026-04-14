# Changelog

All notable changes to the Slogr Agent (Kotlin/JVM) are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.7] - 2026-04-14

### Added
- Ground-truth RTT fields (`rtt_min_ms`, `rtt_avg_ms`, `rtt_max_ms`) in `check --format json` output
- `--traceroute-mode icmp|tcp|udp` flag to force single traceroute mode (no fallback)
- `--traceroute-timeout <seconds>` flag to cap traceroute fallback time (default: 60s)
- Actionable error message when TWAMP reflector cannot bind port 862 (BindException)

### Changed
- `check --traceroute` uses reduced defaults: 1 probe/hop, 1s timeout (vs daemon's 2/2s)
- Text output adds "RTT total" line showing ground-truth bidirectional RTT

### Fixed
- AGENT-001: Port 862 bind failure prints fix instructions instead of raw stack trace
- AGENT-003: Traceroute worst-case drops from 360s to ~90s (first mode + budget cap)
- AGENT-004: `check --format json` was missing ground-truth RTT despite computing them
- `--help` printed null instead of formatted help text (Clikt `PrintHelpMessage.message` is null)
- Unknown commands exited 0 instead of exit code 2 (root command silently swallowed unknown tokens)

## [1.0.5] - 2026-04-09

### Changed
- **Ground-truth RTT** — new `rtt_min_ms`, `rtt_avg_ms`, `rtt_max_ms` fields computed
  as `(T4-T1) - (T3-T2)` using same-clock timestamp pairs. Always clock-independent.
- **Directional split anchored to RTT** — `fwd_avg_rtt_ms` and `rev_avg_rtt_ms` now
  represent the ratio-scaled portion of the ground-truth RTT. Guaranteed: `fwd + rev == rtt`.
  UNSYNCABLE mode sets fwd/rev to 0 (unavailable) instead of fabricating RTT/2.
- **SLA grading uses full RTT** — evaluator now compares `rtt_avg_ms` (round-trip) against
  profile thresholds instead of `fwd_avg_rtt_ms` (one-way delay). Jitter evaluated as
  `max(fwd, rev)` instead of forward-only.

### Migration Note (L2 / ClickHouse)
The three new columns must be added as `Nullable(Float32)` — NOT `Float32 DEFAULT 0`.
Nullable ensures NULL = "agent didn't send this field" (v1.0.4) while 0.0 = "agent
measured sub-millisecond RTT" (v1.0.5+). Any query or view that reads RTT must use:
```sql
COALESCE(rtt_avg_ms, fwd_avg_rtt_ms) AS primary_rtt_ms
```
No NULLIF needed — NULL rows naturally fall back via COALESCE. This applies to
`measurements_unified`, rollup tables, and all dashboard/enterprise queries.
Same pattern for `rtt_min_ms` and `rtt_max_ms`.

### Fixed
- Stale comment in DaemonCommand.kt line 129 (FIX-3 from v1.0.4 backlog)

## [1.0.4] - 2026-04-08

### Added
- **Fixed TWAMP test port** (`SLOGR_TEST_PORT` env var). When set (default 863 in
  Docker), all test session reflectors bind to that port with SO_REUSEPORT instead
  of ephemeral. Reduces firewall rules from `UDP 1024-65535` to `UDP 863`.
- JNI `setReusePort()` function for SO_REUSEPORT on Linux.
- 3-arg `createSocket(ip, port, reusePort)` in NativeProbeAdapter with default
  fallback for backwards compatibility.
- `testUdpPort` field in AgentConfig, read from `SLOGR_TEST_PORT` env var.
- Dockerfile: `ENV SLOGR_TEST_PORT=863`, `EXPOSE 862/tcp 862/udp 863/udp`.
- TWAMP controller state transition logging (INFO level) — greeting, server-start,
  accept-session, start-ack, sender-start all logged with details.
- MeasurementEngineImpl logs sent/recv counts on TWAMP completion.
- TwampSessionReflector lifecycle logging — socket bind, thread start, exit with
  packet count.
- TwampSessionSender logs first 3 sent packets with byte count and target.

## [1.0.3] - 2026-04-07

### Fixed
- **onComplete never fires on connection failure** (BUG-1 HIGH). Both
  `openConnection()` catch block and `closeWithCallback()` now fire the callback
  with error result. Prevents indefinite coroutine hang.
- **targetPort ignored** (BUG-2 HIGH). `reflectorPort` parameter added to
  `TwampController.connect()` and `ConnectRequest`. `--port` flag now works.
- **No invokeOnCancellation** (BUG-3 LOW). Safety net added to
  `suspendCancellableCoroutine` in `runTwamp()`.
- **Partial TCP read data loss**. Per-session `accumBuf` in
  `TwampControllerSession.read()` preserves bytes across TCP reads. A 64-byte
  ServerGreeting arriving as 40+24 is now handled correctly.
- **closeWithCallback state always CLOSED**. `closeReason` captured before
  `close()` resets state, so error messages are diagnostic.
- CheckCommand prints TWAMP failure reason to stderr before ICMP fallback.

## [1.0.2] - 2026-04-07

### Fixed
- **Check command hang** (B2 HIGH). `startReflector=false` for check command —
  client-only mode skips reflector binding. Fixes hang when reflector can't bind
  port 862 (already in use by daemon). Commit `0b18a14`, merged `134c3dd`.

## [1.0.1] - 2026-04-06

### Added
- RabbitMqPublisher wired into DaemonCommand result callback. Agents now publish
  TWAMP/health/traceroute measurements to RabbitMQ. Commit `42752c6`.

## [1.0.0] - 2026-04-05

### Added
- Initial release. Kotlin/JVM TWAMP agent with RFC 5357 controller and reflector.
- NIO Selector event loop for TCP control, ScheduledExecutorService for UDP timing.
- 24 SLA profiles (VoIP, gaming, streaming, PCoIP, video conferencing, etc.).
- CLI: `check`, `daemon`, `version`, `status`, `doctor`, `connect`, `disconnect`.
- Disconnected mode with OTLP export.
- JNI native library for UDP socket operations and traceroute probes.
- Security hardening: connection limits, IP allowlist, HMAC-SHA1, handshake
  deadline, packet size validation, anti-amplification, buffer pool cap.
- Docker image: `ghcr.io/slogr-io/agent:1.0.0`.
- RPM/DEB packages via Packagecloud.
