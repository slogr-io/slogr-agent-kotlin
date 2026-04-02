# R2 Build Guide

**Status:** Locked
**Extends:** R1 `build-guide/build-guide.md`

---

## Prerequisites

R1 is complete (Phases 0-8, 388+ tests passing). R2 builds on top of R1. All R1 tests must pass throughout R2 development — they are the regression baseline.

## R1 → R2 File-by-File Delta

### Existing R1 Files Modified in R2

| R1 File | What Changes |
|---------|-------------|
| `platform/cli/ConnectCommand.kt` | Use `ApiKeyRegistrar` instead of `BootstrapRegistrar`. Accept `--api-key` flag. 16-field registration body. |
| `platform/cli/DaemonCommand.kt` | Auto-connect when `SLOGR_API_KEY` env var present. State determination on startup. Loud mode logging. |
| `platform/cli/StatusCommand.kt` | Enhanced status output (mode, key prefix, RabbitMQ status, session count). |
| `platform/otlp/OtlpExporter.kt` | Gate OTLP export behind `SLOGR_API_KEY`. Nudge logging when key absent. |
| `platform/otlp/MetricMapper.kt` | Add clock sync OTLP metrics (`slogr.network.clock.*`). |
| `platform/output/TextResultFormatter.kt` | Conditional CLI footer (slogr.io vs slogr.io/enterprise). Only on `check` text output. |
| `platform/config/AgentConfig.kt` | `SLOGR_API_KEY` env var reading. New traceroute config properties (4 new fields). State determination logic. |
| `platform/commands/PushConfigHandler.kt` | Handle 4 new traceroute config fields. Partial apply semantics. |
| `contracts/src/.../MeasurementResult.kt` | Add `clockSyncStatus`, `estimatedClockOffsetMs` fields. |
| `engine/assembler/MeasurementAssembler.kt` | Apply virtual clock correction before aggregation. |
| `engine/reflector/TwampSessionReflector.kt` | Swap thread-per-session internals to thread pool dispatch. Same interface. |
| `engine/twamp/TwampControlHandler.kt` | Negotiate mode=4 in ServerGreeting/SetupResponse. |
| `engine/twamp/TwampSessionSender.kt` | Encrypt outbound packets when mode=4. |
| `engine/pathchange/PathChangeDetector.kt` | Add heartbeat cycle counter, forced refresh timer. Verify baselines keyed by `(session_id, direction)`. |
| `native/src/twampUdp.c` | Return `SO_TIMESTAMPING` kernel timestamp alongside packet data from `recvmsg()`. Fallback to `clock_gettime` when CMSG_DATA empty. Add `timestamp_source` flag. |
| `native/src/slogr_native.h` | Add `timestamp_source` field to JNI return struct. |
| `platform/health/HealthReporter.kt` | Add `active_responder_sessions`, `pool_size`, `pool_active_threads` to health signal. |
| `platform/credential/MachineIdentity.kt` | Delegate fingerprint to `PersistentFingerprint.get()` instead of dynamic computation. |
| `platform/buffer/WriteAheadLog.kt` | Add size+age eviction logic. 500MB cap, 72h age limit. |
| `contracts/src/.../ProbeResult.kt` | Add `probeMode` enum field (ICMP_AND_TCP, TCP_ONLY, ICMP_ONLY, BOTH_FAILED). |
| `engine/probe/IcmpPingProbe.kt` | Compute probe mode based on ICMP + TCP results. |
| `platform/output/TextResultFormatter.kt` | Display "ICMP filtered (TCP healthy)" instead of "100% loss" when probe_mode=TCP_ONLY. |
| `platform/output/JsonResultFormatter.kt` | Include `probe_mode` in JSON output. |
| `platform/commands/CommandDispatcher.kt` | Register `halt_measurement` as 6th command type. |
| `platform/cli/SlogrCli.kt` | Register `doctor` subcommand. |
| `platform/commands/SetScheduleHandler.kt` | Parse `tcp_probe_ports` from target payload. Validate max 5 ports. Default to `[443]` when omitted. |
| `engine/probe/TcpConnectProbe.kt` | Iterate over `tcp_probe_ports` array. 2-second timeout per port. One `ProbeResult` per port. Sequential, not parallel. |

### R1 Files Deleted in R2

| File | Reason |
|------|--------|
| `registration/BootstrapRegistrar.kt` | Replaced by `ApiKeyRegistrar.kt` |

### New Files Created in R2

| New File | Purpose |
|----------|---------|
| `integration/registration/ApiKeyRegistrar.kt` | API key registration (replaces BootstrapRegistrar) |
| `integration/registration/FreeKeyValidator.kt` | `GET /v1/keys/validate` + local cache for `sk_free_*` |
| `integration/registration/KeyValidationCache.kt` | Reads/writes `/var/lib/slogr/key_validation.json` |
| `platform/config/AirGapDetector.kt` | DNS resolution check for slogr.io |
| `platform/config/ConfigWatcher.kt` | Watches config file for key changes, triggers reload |
| `engine/reflector/ReflectorThreadPool.kt` | Thread pool manager for UDP reflector |
| `engine/reflector/ReflectorSession.kt` | Per-session state (socket, sender address, packet count) |
| `engine/reflector/PacketBufferPool.kt` | Pre-allocated ByteBuffer pool |
| `engine/clock/VirtualClockEstimator.kt` | Per-responder clock offset estimation |
| `engine/clock/ClockSyncDetector.kt` | SYNCED/ESTIMATED/UNSYNCABLE classification |
| `engine/twamp/TwampCrypto.kt` | AES-CBC encrypt/decrypt, HMAC-SHA1, KDF for mode=4 |
| `platform/identity/PersistentFingerprint.kt` | File-backed machine fingerprint. Survives clones/container restarts. |
| `platform/health/PrometheusExporter.kt` | Embedded HTTP server on 127.0.0.1:9090/metrics. No key required. |
| `platform/cli/DoctorCommand.kt` | Diagnostic command: verifies JNI, CAP_NET_RAW, TLS connectivity |
| `platform/commands/HaltMeasurementHandler.kt` | Kill switch: purges in-memory schedules on remote command |

---

## R2 Phases

### Phase 1: Registration, Keys, and PLG (3-4 days)

**Goal:** Replace bootstrap token with API key. Implement three-state model. Gate OTLP. Add CLI nudges.

**Build order:**
1. `ApiKeyRegistrar.kt` — replaces `BootstrapRegistrar.kt`
2. `FreeKeyValidator.kt` — `GET /v1/keys/validate` + local cache
3. `KeyValidationCache.kt` — reads/writes `/var/lib/slogr/key_validation.json`
4. Update `AgentConfig.kt` — `SLOGR_API_KEY` env var, state determination
5. Update `DaemonCommand.kt` — auto-connect when key present + no credential
6. Update `OtlpExporter.kt` — state check, nudge logging
7. `AirGapDetector.kt` — DNS resolution check
8. Update `TextResultFormatter.kt` — conditional footer
9. `ConfigWatcher.kt` — file watcher for hot key reload
10. Update `ConnectCommand.kt` — use `ApiKeyRegistrar`
11. Delete `BootstrapRegistrar.kt`

**Tests:** R2-REG-01 through R2-REG-09, R2-KEY-01 through R2-KEY-05, R2-OTLP-01 through R2-OTLP-04, R2-STATE-01 through R2-STATE-04, R2-AIRGAP-01 through R2-AIRGAP-03.

**Deliverable:** `slogr-agent daemon` works in all three states. OTLP gate enforced. Air-gapped detection works. CLI footer shows appropriate nudge.

### Phase 2: Thread Pool Reflector (3-5 days)

**Goal:** Replace thread-per-session with thread pool for scalable reflector.

**Build order:**
1. `ReflectorSession.kt` — per-session state (socket, sender address, packet count)
2. `PacketBufferPool.kt` — pre-allocated ByteBuffer pool
3. `ReflectorThreadPool.kt` — thread pool manager, dispatches packets to workers
4. Modify `twampUdp.c` — return `SO_TIMESTAMPING` kernel timestamp with packet data
5. Modify `TwampSessionReflector.kt` — swap from thread-per-session to pool dispatch
6. Update health signal — add `active_responder_sessions`, `pool_size`, `pool_active_threads`

**Tests:** R2-POOL-01 through R2-POOL-05. Load test with 1000 concurrent sessions.

**Deliverable:** Reflector handles 1000+ concurrent sessions. T2 timestamps accurate at kernel level. Memory usage proportional to pool size, not session count.

### Phase 3: Virtual Clock Estimation (1-2 days)

**Goal:** Estimate clock offset for one-way delay when NTP sync unavailable.

**Build order:**
1. `VirtualClockEstimator.kt` — per-responder offset estimation
2. `ClockSyncDetector.kt` — SYNCED/ESTIMATED/UNSYNCABLE classification
3. Update `MeasurementResult.kt` — add `clockSyncStatus`, `estimatedClockOffsetMs`
4. Update `MeasurementAssembler.kt` — apply clock correction before aggregation
5. Update `MetricMapper.kt` — add clock sync OTLP metrics

**Tests:** R2-CLOCK-01 through R2-CLOCK-04.

**Deliverable:** One-way delays have quality indicators. IPDV always accurate. Unsyncable paths fall back to RTT/2.

### Phase 4: Encrypted TWAMP (3-5 days)

**Goal:** AES-CBC encryption for TWAMP packets (RFC 5357 mode=4).

**Build order:**
1. `TwampCrypto.kt` — AES-CBC encrypt/decrypt, HMAC-SHA1, KDF
2. Update `TwampControlHandler.kt` — negotiate mode=4 in ServerGreeting/SetupResponse
3. Update `TwampSessionSender.kt` — encrypt outbound packets when mode=4
4. Update `TwampSessionReflector.kt` — decrypt inbound, encrypt outbound when mode=4

**Tests:** Known test vectors from RFC. Fallback to mode=1 when remote doesn't support mode=4.

**Deliverable:** Encrypted TWAMP sessions work end-to-end. Graceful fallback to unauthenticated.

### Phase 5: Packaging & Distribution (3-5 days)

**Goal:** 9 distribution formats, all signed, all silent-install capable.

**Build order:**
1. RPM spec file (using fpm or rpmbuild)
2. DEB spec file (using fpm or dpkg-deb)
3. Dockerfile (multi-arch: amd64 + arm64)
4. Helm chart (DaemonSet template, values.yaml, Secret reference)
5. MSI installer project (WiX or jpackage + msi)
6. PKG installer project (pkgbuild)
7. Homebrew formula (tap repo on GitHub)
8. Chocolatey nuspec
9. `install.sh` universal installer script
10. GitHub Actions CI pipeline for build + publish

**Tests:** R2-PKG-01 through R2-PKG-08.

**Deliverable:** All 9 formats published. CI pipeline automates build + publish on tag push.

### Phase 6: Testing & Hardening (2-3 days)

**Goal:** End-to-end validation of all R2 features.

**Tests:**
- All R2-specific tests (R2-REG through R2-PKG)
- R1 regression baseline (388 tests must still pass)
- Egress optimization tests (R2-EGRESS-01 through R2-EGRESS-05)
- Bidirectional traceroute tests (R2-BIDIR-01 through R2-BIDIR-03)
- Mass deployment test (R2-MASS-01 through R2-MASS-03)
- Load test: 1000 concurrent sessions, 4-core VM, 1 hour sustained
- Key rotation test: change key, verify hot reload, verify no data loss

**Deliverable:** All tests pass. Agent is production-ready for 200+ mesh deployment.

## Phase Completion Checklist

After each phase:
1. All new tests pass
2. All R1 regression tests (388) still pass
3. `./gradlew test` exits 0 with no skipped tests (except Linux-only JNI tests on non-Linux)
4. Code reviewed by Gemini
5. Progress reported: "Phase N complete — X/Y tests passing"

## Total R2 Effort

| Phase | Days |
|-------|------|
| 1: Registration, Keys, PLG | 3-4 |
| 2: Thread Pool Reflector | 3-5 |
| 3: Virtual Clock | 1-2 |
| 4: Encrypted TWAMP | 3-5 |
| 5: Packaging | 3-5 |
| 6: Testing | 2-3 |
| **Total** | **15-24 days** |
