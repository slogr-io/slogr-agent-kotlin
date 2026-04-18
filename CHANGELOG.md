# Changelog

All notable changes to the Slogr Agent (Kotlin/JVM) are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 1.0.0 (2026-04-18)


### Features

* **asn:** zero-config ip2asn ISP detection for v1.0.6 ([13cca5a](https://github.com/slogr-io/slogr-agent-kotlin/commit/13cca5afdb65fe6a86cedecd076e43bea1b00bee))


### Bug Fixes

* **ci:** build native library before tests; add R2 branch trigger ([2bb9b42](https://github.com/slogr-io/slogr-agent-kotlin/commit/2bb9b425c344531c10f5982914690874b9abd385))
* **ci:** build native library before tests; add R2 branch trigger ([2c53dda](https://github.com/slogr-io/slogr-agent-kotlin/commit/2c53dda4f464e7e776bdf33c7e2783ba59ed916b))
* **ci:** download artifact to workspace root to restore original path ([2545d03](https://github.com/slogr-io/slogr-agent-kotlin/commit/2545d0364276d994072739a93fea83016c5e3eed))
* **ci:** remove musl-tools from test job — it breaks glibc JNI tests ([79efd4d](https://github.com/slogr-io/slogr-agent-kotlin/commit/79efd4d84d89973db12a8ea23e0adafc4d73f199))
* **ci:** stage JAR into deploy/ before docker build — app/build/ is dockerignored ([02e4778](https://github.com/slogr-io/slogr-agent-kotlin/commit/02e4778f0371510229b7a9c7c425ccdf4d0a068d))
* **cli:** add -f short alias for --format in check command ([2e8d772](https://github.com/slogr-io/slogr-agent-kotlin/commit/2e8d772e3144b4d08d25f24f988cc1542eab5c05))
* daemon binds 0.0.0.0:862 and respects --config schedule file ([af88672](https://github.com/slogr-io/slogr-agent-kotlin/commit/af886725ec5e18b0a88021e76550c9f239842fbb))
* **daemon:** re-loop on InterruptedException to keep main thread alive ([a225e6b](https://github.com/slogr-io/slogr-agent-kotlin/commit/a225e6b504b1cf19de66692167639b8cb808a7d3))
* **docker:** install bash in runtime stage — wrapper.sh requires bash not sh ([7b2584a](https://github.com/slogr-io/slogr-agent-kotlin/commit/7b2584af5d375918026014076feb65e232cece3a))
* **docker:** install bash in runtime stage — wrapper.sh requires bash not sh ([196d705](https://github.com/slogr-io/slogr-agent-kotlin/commit/196d705ad28dba6c8d6b69cf4f674bd9ebcbb622))
* **docker:** move native libs to native/libs/ to avoid .dockerignore exclusion ([ca933b0](https://github.com/slogr-io/slogr-agent-kotlin/commit/ca933b08fd435b703411bdde5221bbe8007f8e9d))
* **docker:** move native libs to native/libs/ to avoid .dockerignore exclusion ([11f2c1b](https://github.com/slogr-io/slogr-agent-kotlin/commit/11f2c1b10fcb10fb4b78d9d660590180c70f03fc))
* **docker:** un-exclude native/build/libs/ from Docker build context ([732d4db](https://github.com/slogr-io/slogr-agent-kotlin/commit/732d4dbd576656990659e8567f166c9675d3f964))
* **docker:** un-exclude native/build/libs/ from Docker build context ([8a08ab2](https://github.com/slogr-io/slogr-agent-kotlin/commit/8a08ab257b1f4222952c527bba4c41198c5b74e1))
* **e2e:** bind port 862 as non-root via setcap; harden reflector error logging ([9172f54](https://github.com/slogr-io/slogr-agent-kotlin/commit/9172f544bb3ae84226edcc71cba037c0ccb16ef6))
* **e2e:** revert setcap (breaks musl libjli), run daemon as root instead ([1acf760](https://github.com/slogr-io/slogr-agent-kotlin/commit/1acf760f6774ba4ab2cb1074c76ce8fd5d65b8e0))
* log reflector bind failures via SLF4J and fix KeyValidationCache serialization ([0403e95](https://github.com/slogr-io/slogr-agent-kotlin/commit/0403e95842243f54a38fca23650ef6bddaacf2d7))
* log reflector bind failures via SLF4J and fix KeyValidationCache serialization ([b471fc8](https://github.com/slogr-io/slogr-agent-kotlin/commit/b471fc8d5f4371556f04641cbe025e165046bd1a))
* **logging:** route logs to stderr so stdout carries only JSON output ([b6d29aa](https://github.com/slogr-io/slogr-agent-kotlin/commit/b6d29aa48af1e2c2209f765d170e229044290c9e))
* **R2:** add kotlinx.serialization plugin + dep to platform module ([78445f3](https://github.com/slogr-io/slogr-agent-kotlin/commit/78445f3cc0fe307d10a541748724293d53ff16f2))
* **R2:** remove duplicate fun start() in MeasurementEngine interface ([8ff89a9](https://github.com/slogr-io/slogr-agent-kotlin/commit/8ff89a9adad74a4fda238e6e495a9e0911d8628f))
* start TWAMP reflector eagerly at daemon startup (responder-only mode) ([7d27116](https://github.com/slogr-io/slogr-agent-kotlin/commit/7d271169f2425a68cafed93afbfb9ea8a08168ca))
* **test:** use AtomicInteger in TestSchedulerTest to eliminate data race ([9a272a2](https://github.com/slogr-io/slogr-agent-kotlin/commit/9a272a24e7a5d8346cbe6b31694c4ddf2a2af568))


### Reverts

* remove KeyValidationCache.kt from master (belongs only in R2) ([fd22290](https://github.com/slogr-io/slogr-agent-kotlin/commit/fd222902acde4e2b5d9f90d16c60808a1cf849a3))

## [1.0.6] - 2026-04-09

### Added
- Zero-config ASN/ISP resolution using ip2asn database (IPtoASN.com, public domain)
- Ip2AsnResolver: sorted-array binary search over ~700K IPv4 ranges, sub-millisecond lookups
- Bundled ip2asn-v4.tsv.gz (~3MB) in JAR — ASN works on first boot with no network access
- AsnDatabaseUpdater: 5-tier download chain (slogr.io → iptoasn.com → disk cache → bundled → null)
- First-run deployment analytics: agent pings data.slogr.io on install for fleet visibility
- SwappableAsnResolver: thread-safe runtime hot-swap for 30-day daemon refresh cycle
- Cloudflare Worker at data.slogr.io/asn-db serving cached database + logging analytics

### Changed
- ASN resolver priority: MaxMind (if configured) > ip2asn (automatic) > NullAsnResolver
- setup-asn command now notes ip2asn is automatic; MaxMind is optional for higher accuracy

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
