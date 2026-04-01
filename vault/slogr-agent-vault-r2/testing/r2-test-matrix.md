# R2 Test Matrix

**Status:** Locked
**Extends:** R1 `testing/strategy.md` — all R1 tests remain as regression baseline (388 tests)

---

## R2-Specific Test Scenarios

### Registration & Key Model

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-REG-01 | `slogr-agent connect --api-key sk_live_abc123` with valid key | Registration succeeds, credential stored, CONNECTED mode |
| R2-REG-02 | Same machine fingerprint, same tenant → reconnect | Returns SAME agent_id (reactivation) |
| R2-REG-03 | Same machine fingerprint, different tenant | Returns NEW agent_id |
| R2-REG-04 | Invalid key format (no `sk_` prefix) | Exit code 4, error message |
| R2-REG-05 | Valid format but revoked key (401 from server) | Log warning, start ANONYMOUS |
| R2-REG-06 | Network error during registration | Retry with backoff, run ANONYMOUS while retrying |
| R2-REG-07 | `SLOGR_API_KEY=sk_live_*` + no credential → daemon auto-registers | Auto-registration, CONNECTED mode |
| R2-REG-08 | `SLOGR_API_KEY=sk_live_*` + existing credential → daemon skips registration | Uses stored credential, CONNECTED mode |
| R2-REG-09 | `slogr-agent disconnect` | Credential deleted, daemon continues ANONYMOUS |

### Free Key Validation

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-KEY-01 | `SLOGR_API_KEY=sk_free_*`, valid key (200 from /v1/keys/validate) | Cached, REGISTERED mode, OTLP enabled |
| R2-KEY-02 | `SLOGR_API_KEY=sk_free_*`, invalid key (401) | Warning logged, ANONYMOUS mode |
| R2-KEY-03 | `SLOGR_API_KEY=sk_free_*`, network error | Trust format, REGISTERED mode |
| R2-KEY-04 | Cached validation < 24h old | Skip validation, use cached result |
| R2-KEY-05 | Cached validation > 24h old | Re-validate |

### OTLP Gate

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-OTLP-01 | No `SLOGR_API_KEY` → OTLP export attempted | Export blocked, nudge logged once |
| R2-OTLP-02 | `sk_free_*` key set | OTLP export works |
| R2-OTLP-03 | `sk_live_*` key set | OTLP export works |
| R2-OTLP-04 | Key removed at runtime (hot reload) | OTLP export stops, agent drops to ANONYMOUS |

### Three-State Model

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-STATE-01 | No key → daemon start | ANONYMOUS mode, log says "ANONYMOUS mode (stdout only)" |
| R2-STATE-02 | `sk_free_*` → daemon start | REGISTERED mode, log says "REGISTERED mode (OTLP + stdout)" |
| R2-STATE-03 | `sk_live_*` → daemon start | CONNECTED mode, log says "CONNECTED mode" |
| R2-STATE-04 | State transition without restart (SIGHUP) | Mode changes, log confirms new state |

### Thread Pool Reflector

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-POOL-01 | 100 concurrent sessions on 2-core VM | All sessions handled, no packet loss |
| R2-POOL-02 | 1000 concurrent sessions on 4-core VM | All sessions handled, T2 timestamps accurate |
| R2-POOL-03 | Pool exhaustion (more sessions than pool × 4) | Packets queue, no crash, RTT accuracy maintained |
| R2-POOL-04 | Session cleanup after REFWAIT timeout | Session removed from ConcurrentHashMap, socket closed |
| R2-POOL-05 | Verify T2 kernel timestamp vs Java timestamp | Kernel T2 captured before thread pool dispatch, not after |

### Virtual Clock Estimation

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-CLOCK-01 | Both agents NTP-synced | `clock_sync_status = SYNCED`, raw one-way delays used |
| R2-CLOCK-02 | 100ms artificial clock skew | `clock_sync_status = ESTIMATED`, corrected delays within ±5ms |
| R2-CLOCK-03 | 10-second clock skew (badly broken NTP) | `clock_sync_status = UNSYNCABLE`, fwd/rev = RTT/2 |
| R2-CLOCK-04 | One-way jitter accuracy regardless of sync | IPDV values identical across all sync states |

### Air-Gapped Detection

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-AIRGAP-01 | DNS resolution of slogr.io succeeds | `isAirGapped = false`, footer shows "slogr.io" |
| R2-AIRGAP-02 | DNS resolution blocked | `isAirGapped = true`, footer shows "slogr.io/enterprise" |
| R2-AIRGAP-03 | DNS timeout (3 seconds) | `isAirGapped = true` |

### Egress Optimization

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-EGRESS-01 | Stable path, 10 cycles | 0 change publishes, 1-2 heartbeats (depending on heartbeat_interval_cycles) |
| R2-EGRESS-02 | Path changes at cycle 3 | 1 change publish at cycle 3, heartbeats resume after |
| R2-EGRESS-03 | Forced refresh with unchanged path | `is_forced_refresh=true`, `is_heartbeat=true` |
| R2-EGRESS-04 | Forced refresh with changed path | `is_forced_refresh=true`, `is_heartbeat=false`, `changed_hops` populated |
| R2-EGRESS-05 | Heartbeat absence detection | No traceroute for >35 min → Layer 2 should alert |

### Bidirectional Traceroute

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-BIDIR-01 | Agent A traces to B, Agent B traces to A | Both publish with correct `path_id` and `direction=UPLINK` |
| R2-BIDIR-02 | Asymmetric path (different ISPs each direction) | Different `asn_path` in each direction's traceroute |
| R2-BIDIR-03 | Path change in one direction only | Only the changed direction publishes a path_change. Other stays heartbeat. |

### Mass Deployment

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-MASS-01 | 10 agents, same `sk_live_*` key | 10 unique agent_ids, all appear in agent directory |
| R2-MASS-02 | Key rotation: push new key, revoke old | Agents on old key drop to ANONYMOUS, reconnect with new key |
| R2-MASS-03 | Idempotent install (run installer twice) | No duplicate agent, no corrupted state |

### Encrypted TWAMP

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-ENC-01 | Both agents support mode=4 | Encrypted session established, packets encrypted with AES-CBC, measurements accurate |
| R2-ENC-02 | Remote reflector only supports mode=1 | Graceful fallback to unauthenticated, no error, measurements work |
| R2-ENC-03 | Known test vectors from RFC | Encrypt/decrypt output matches RFC test vectors exactly |
| R2-ENC-04 | HMAC verification failure (tampered packet) | Packet rejected, logged as warning, not counted in measurements |
| R2-ENC-05 | Mode negotiation in ServerGreeting | ServerGreeting advertises `modes=0x05`, client selects mode=4 |

### Push Config Extended

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-CFG-01 | Push all 8 fields | All 8 applied, agent ACKs |
| R2-CFG-02 | Push only `traceroute_heartbeat_interval_cycles: 12` | Only that field changes, other 7 retain previous values |
| R2-CFG-03 | Push invalid value (`traceroute_max_hops: -1`) | Agent rejects with error, no config change, status=failed |
| R2-CFG-04 | Push empty payload `{}` | Agent ACKs, no config change (all fields optional) |
| R2-CFG-05 | Push `traceroute_fallback_modes: ["TCP", "UDP"]` (no ICMP) | Agent uses TCP-first traceroute, skips ICMP |
| R2-CFG-06 | Config persists across restart | Restart agent, verify pushed config is still active |

### Health Signal (R2 Fields)

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-HEALTH-01 | 50 active responder sessions | Health signal includes `active_responder_sessions: 50` |
| R2-HEALTH-02 | Thread pool at capacity | `pool_active_threads` equals `pool_size`, `pool_queue_depth > 0` |
| R2-HEALTH-03 | No responder sessions | `active_responder_sessions: 0`, `pool_active_threads: 0` |

### Packaging

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-PKG-01 | RPM install + service start | slogr-agent service running, `slogr-agent version` works |
| R2-PKG-02 | DEB install + service start | Same |
| R2-PKG-03 | Docker run with `SLOGR_API_KEY` env var | Agent auto-connects, daemon running |
| R2-PKG-04 | MSI silent install with `SLOGR_API_KEY` property | Windows Service registered and running |
| R2-PKG-05 | PKG install on macOS | launchd service running |
| R2-PKG-06 | `curl installer.sh \| sh` | Correct package installed for OS/arch |
| R2-PKG-07 | Helm install on K8s | DaemonSet running, one pod per node |
| R2-PKG-08 | Clean uninstall (all formats) | Binary, config, service removed. Credential and WAL optionally preserved. |

### JNI Timestamp Fallback

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-TS-01 | Kernel SO_TIMESTAMPING available | `timestamp_source = KERNEL`, T2 precision < 100µs |
| R2-TS-02 | SO_TIMESTAMPING unavailable (CMSG_DATA empty) | Fallback to `clock_gettime`, `timestamp_source = USERSPACE`, no crash |
| R2-TS-03 | Userspace timestamp with virtual clock estimator | `ClockSyncDetector` never reports SYNCED, only ESTIMATED or UNSYNCABLE |

### Persistent Fingerprint

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-FP-01 | First boot — no fingerprint file | Generate fingerprint, write to `/var/lib/slogr/.agent_fingerprint` |
| R2-FP-02 | Second boot — fingerprint file exists | Read from file, same fingerprint as first boot |
| R2-FP-03 | Two cloned VMs with identical MAC+hostname | Different fingerprints (UUID component ensures divergence) |
| R2-FP-04 | Docker container restart (new MAC) | Same fingerprint (read from mounted volume) |

### WAL Eviction

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-WAL-01 | WAL grows past 500MB | Oldest entries evicted, total size ≤ 500MB |
| R2-WAL-02 | WAL entries older than 72 hours | Expired entries evicted on next append |
| R2-WAL-03 | WAL eviction under normal operation | No eviction, all entries retained |
| R2-WAL-04 | Disk with only 100MB free | WAL respects limit, does not fill remaining disk |

### Probe Mode Classification

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-PROBE-01 | ICMP works + TCP works | `probe_mode = ICMP_AND_TCP` |
| R2-PROBE-02 | ICMP blocked + TCP works | `probe_mode = TCP_ONLY`, NOT "100% loss" |
| R2-PROBE-03 | ICMP works + TCP fails | `probe_mode = ICMP_ONLY` |
| R2-PROBE-04 | Both fail | `probe_mode = BOTH_FAILED` |

### Prometheus Exporter

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-PROM-01 | Daemon running, curl localhost:9090/metrics | 200 OK, valid Prometheus exposition format |
| R2-PROM-02 | No SLOGR_API_KEY set (ANONYMOUS mode) | Exporter still works — NOT gated |
| R2-PROM-03 | Exporter binds to 127.0.0.1 only | Connection from external IP rejected |

### Doctor Command

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-DOC-01 | All checks pass | "All checks passed." exit code 0 |
| R2-DOC-02 | CAP_NET_RAW missing | Specific remediation: "Run: sudo setcap..." |
| R2-DOC-03 | JNI library not found | Specific remediation with library path |
| R2-DOC-04 | slogr.io unreachable | Reports air-gapped, skips TLS/API checks |

### Kill Switch

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-HALT-01 | halt_measurement received | All sessions stopped, schedule purged, agent stays connected |
| R2-HALT-02 | set_schedule after halt | Agent resumes measurements with new schedule |
| R2-HALT-03 | halt_measurement timeout (30s) | Status → timed_out |
| R2-HALT-04 | Health signals after halt | Still sending — agent is connected, just idle |

### Multi-Port TCP Probe

| Test ID | Scenario | Expected |
|---------|----------|----------|
| R2-MPORT-01 | `tcp_probe_ports: [443, 1433, 6379]` — all reachable | 3 `probe_raw` rows, all `tcp_success: true`, each with correct `tcp_port` |
| R2-MPORT-02 | `tcp_probe_ports: [443, 6379]` — 443 open, 6379 blocked | 2 rows: 443 success, 6379 `tcp_success: false`, `tcp_connect_ms: null` |
| R2-MPORT-03 | `tcp_probe_ports` omitted | Default to `[443]`, single probe_raw row |
| R2-MPORT-04 | `tcp_probe_ports: [443, 80, 8080, 3306, 5432, 6379]` — 6 ports (exceeds max) | Agent rejects with error, uses first 5 only, logs warning |
| R2-MPORT-05 | TCP connect timeout — blocked port | Connect attempt times out within 2 seconds, does not block subsequent port probes |
| R2-MPORT-06 | All ports blocked + ICMP blocked | `probe_mode: BOTH_FAILED` for each port, no false "100% loss" on individual ports |
