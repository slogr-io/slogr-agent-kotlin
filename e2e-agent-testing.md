# E2E Agent Testing

Manual and semi-automated end-to-end tests for the Slogr Kotlin agent.
Run before every merge to master.

## Test Tags

- **regression-testing** — run on EVERY build. These cover core functionality that must never break.
- **extended-testing** — run on minor/major releases or when the related subsystem changed. Deeper validation.

## Prerequisites

- Agent JAR built: `./gradlew :app:shadowJar`
- Unit tests passing: `./gradlew build` (zero failures, zero warnings)
- Two hosts available: Windows (laptop) + Linux (GCP VM)
- TWAMP port 862 open (TCP + UDP) on both hosts
- RabbitMQ credentials available for CONNECTED mode tests (or skip those)

## Environment Setup

```bash
# Set on both hosts before testing
export SLOGR_TEST_PORT=863
# For CONNECTED mode tests only:
export SLOGR_API_KEY=sk_live_...
```

---

## R-01: Version Command [regression-testing]

```bash
slogr-agent version
```

**Verify:**
- [ ] Prints version string matching build.gradle.kts (e.g., `1.0.6-SNAPSHOT` or `1.0.6`)
- [ ] Exit code 0
- [ ] No stack traces or warnings

---

## R-02: Status Command [regression-testing]

```bash
slogr-agent status
```

**Verify:**
- [ ] Prints agent state (ANONYMOUS, REGISTERED, or CONNECTED)
- [ ] Shows data directory path
- [ ] Exit code 0

---

## R-03: Check Command — Reachable Target [regression-testing]

```bash
slogr-agent check <GCP-target-IP>
```

**Verify:**
- [ ] TWAMP measurement completes without error
- [ ] rtt_avg_ms is a positive number
- [ ] packets_sent > 0 and packets_recv > 0
- [ ] SLA grade printed (GREEN, YELLOW, or RED)
- [ ] Exit code 0
- [ ] Completes in <30 seconds

---

## R-04: Check Command — Unreachable Target [regression-testing]

```bash
slogr-agent check 192.0.2.1
```

**Verify:**
- [ ] TWAMP fails gracefully (no crash, no hang)
- [ ] Falls back to ICMP/TCP probe
- [ ] Exit code 3 (target unreachable)
- [ ] Completes in <60 seconds (timeout, not hang)

---

## R-05: Check Command — JSON Output [regression-testing]

```bash
slogr-agent check <target> --format json
```

**Verify:**
- [ ] Output is valid JSON (parseable by `jq` or equivalent)
- [ ] Contains `twamp` object with rtt_min_ms, rtt_avg_ms, rtt_max_ms, fwd_*, rev_*, packets_sent, packets_recv
- [ ] Contains `grade` field (GREEN/YELLOW/RED)
- [ ] Contains `schema_version` field
- [ ] If traceroute ran: `traceroute` object has `hops` array with hop_ttl, hop_ip, hop_rtt_ms

---

## R-06: Ground-Truth RTT Integrity [regression-testing]

Run `slogr-agent check <target> --format json` and inspect the TWAMP result.

**Verify:**
- [ ] rtt_min_ms, rtt_avg_ms, rtt_max_ms are all populated (not null, not zero for a reachable target)
- [ ] rtt_avg_ms >= rtt_min_ms
- [ ] rtt_avg_ms <= rtt_max_ms
- [ ] fwd_avg_rtt_ms + rev_avg_rtt_ms == rtt_avg_ms (within 0.01ms floating point tolerance)
- [ ] Jitter values (fwd_jitter_ms, rev_jitter_ms) are non-negative

---

## R-07: Traceroute + ASN Enrichment [regression-testing]

```bash
slogr-agent check <target>
```

**Verify:**
- [ ] Traceroute output is printed with hop table
- [ ] Public hops have hop_asn (integer) and hop_asn_name (string)
- [ ] Private hops (10.x, 172.16.x, 192.168.x) show null/empty ASN (expected)
- [ ] At least 2 hops in the path have recognized ISP/carrier ASN names
- [ ] hop_ttl increments sequentially (1, 2, 3, ...)
- [ ] hop_rtt_ms values are reasonable (not negative, not >10000ms)

---

## R-08: ASN Resolution — Known IPs [regression-testing]

This validates the bundled ip2asn database is loaded and functional.
Run `slogr-agent check` against multiple targets and verify ASN in traceroute hops, OR write a quick script that resolves known IPs through the agent.

**Verify (from traceroute hops or logs):**
- [ ] Google DNS (8.8.8.8) → AS15169
- [ ] Cloudflare (1.1.1.1) → AS13335
- [ ] Private IPs → null ASN (no crash)

---

## R-09: Daemon Mode — Start and Run [regression-testing]

```bash
slogr-agent daemon --config schedule.json
# Let run for 3+ minutes with 60-second interval, then Ctrl+C
```

**Verify:**
- [ ] Startup log shows agent state (ANONYMOUS/REGISTERED/CONNECTED)
- [ ] TWAMP reflector binds on port 862
- [ ] Measurements fire on schedule interval
- [ ] Each measurement logs session result with grade
- [ ] Ctrl+C triggers clean shutdown ("Shutting down daemon...")
- [ ] No exceptions or stack traces in logs
- [ ] Exit code 0 after shutdown

---

## R-10: Daemon Mode — Graceful Shutdown [regression-testing]

Start daemon, let it run 2 measurement cycles, then send SIGTERM (or Ctrl+C).

**Verify:**
- [ ] Shutdown hook fires
- [ ] "Shutting down daemon..." message logged
- [ ] No orphan threads (process exits cleanly)
- [ ] If RabbitMQ connected: publisher flushed before exit

---

## R-11: Doctor Command [regression-testing]

```bash
slogr-agent doctor
```

**Verify:**
- [ ] Prints diagnostic checks (port availability, connectivity, config)
- [ ] No crash
- [ ] Exit code 0

---

## R-12: Cross-Platform — Windows [regression-testing]

Run R-03, R-05, R-07, R-09 on Windows.

**Verify:**
- [ ] JavaUdpTransport fallback works (no JNI on Windows)
- [ ] ASN enrichment produces identical results to Linux for same target
- [ ] Data directory defaults to `%USERPROFILE%\.slogr\`
- [ ] No Windows-specific path issues (backslash handling, etc.)

---

## R-13: Cross-Platform — Linux [regression-testing]

Run R-03, R-05, R-07, R-09 on GCP Linux VM.

**Verify:**
- [ ] JNI native transport loads (or JavaUdpTransport fallback if JNI not available)
- [ ] ASN enrichment works
- [ ] Data directory defaults to `~/.slogr/`
- [ ] Docker mode: `--network host` works with port 862

---

## R-14: Startup Performance [regression-testing]

Time the agent startup:

```bash
time slogr-agent version
time slogr-agent check <target>
```

**Verify:**
- [ ] `version` command completes in <3 seconds
- [ ] `check` command starts measurement within 5 seconds of invocation (ip2asn load time included)
- [ ] No noticeable regression from previous version

---

## R-15: Help and Unknown Commands [regression-testing]

```bash
slogr-agent --help
slogr-agent nonexistent-command
```

**Verify:**
- [ ] `--help` prints usage with all subcommands listed (version, status, check, daemon, connect, disconnect, setup-asn, doctor)
- [ ] Unknown command → exit code 2, error message printed

---

## R-16: Multi-Profile Connectivity [regression-testing]

Run check against a remote target using 5 profiles that cover different packet sizes, intervals, and DSCP values. This validates that TWAMP works across the profile spectrum — catches packet fragmentation (large sizes), NAT timeout (slow intervals), and DSCP filtering issues.

```bash
for profile in internet voip gaming download iot; do
  echo "=== $profile ==="
  slogr-agent check <GCP-target-IP> --profile $profile --format json
done
```

**Verify for each profile:**
- [ ] `packets_recv > 0` (at least some packets received)
- [ ] `grade` is computed (GREEN/YELLOW/RED)
- [ ] No crash, no hang, completes within 30 seconds per profile
- [ ] RTT values are in the same ballpark across profiles (±50ms — same path, similar RTT)

**Profile coverage rationale:**

| Profile | Packets | Interval | DSCP | Packet Size | What it tests |
|---------|---------|----------|------|-------------|---------------|
| internet | 10 | 50ms | 8 (CS1) | 1350 | Large packets, slow interval |
| voip | 50 | 20ms | 46 (EF) | 200 | High DSCP, fast interval, many packets |
| gaming | 50 | 20ms | 34 (AF41) | 100 | Small packets, fast interval |
| download | 20 | 50ms | 0 (BE) | 1350 | DSCP 0 (best effort), medium count |
| iot | 30 | 50ms | 0 (BE) | 100 | DSCP 0, small packets |

**Known limitation:** On high-latency paths (>200ms RTT), profiles with 50 packets may receive fewer than sent due to the wait window expiring before all responses arrive. `packets_recv >= packets_sent * 0.5` is acceptable for high-latency paths.

---

## E-01: First-Boot Bundled Database [extended-testing]

On a clean environment (delete `~/.slogr/ip2asn-v4.tsv` and `ip2asn-meta.json`):

```bash
rm -f ~/.slogr/ip2asn-v4.tsv ~/.slogr/ip2asn-meta.json
slogr-agent check <target>
```

**Verify:**
- [ ] Logs show download attempt to data.slogr.io (tier 1 — will fail if Worker not deployed)
- [ ] Logs show fallback to iptoasn.com (tier 2) or bundled resource (tier 4)
- [ ] Traceroute hops show ASN names despite no prior cache
- [ ] `~/.slogr/ip2asn-v4.tsv` created on disk after run
- [ ] `~/.slogr/ip2asn-meta.json` created with `downloaded_at` timestamp

---

## E-02: Download Chain — Tier Fallback [extended-testing]

Test each tier by simulating failures:

**Tier 1 → 2:** (default if Worker not deployed)
- [ ] Agent logs show tier 1 attempt failed, tier 2 succeeded

**Tier 2 → 4 (bundled):** Block iptoasn.com via hosts file or firewall:
```bash
# Temporarily block
echo "127.0.0.1 iptoasn.com" >> /etc/hosts
echo "127.0.0.1 data.slogr.io" >> /etc/hosts
rm -f ~/.slogr/ip2asn-v4.tsv ~/.slogr/ip2asn-meta.json
slogr-agent check <target>
# Restore hosts file after test
```
- [ ] Agent falls through to bundled resource (tier 4)
- [ ] ASN enrichment still works
- [ ] No crash or hang (graceful timeout on HTTP failures)

**Tier 3 (stale cache):** Set metadata downloaded_at to 60 days ago, block network:
- [ ] Agent uses stale cache with warning log

---

## E-03: First-Run Analytics Ping [extended-testing]

Delete metadata file and monitor network traffic:

```bash
rm -f ~/.slogr/ip2asn-meta.json
# Use tcpdump, Wireshark, or proxy to capture HTTP traffic
slogr-agent check <target>
```

**Verify:**
- [ ] Agent makes HTTP request to `data.slogr.io/asn-db?agent_id=<uuid>&v=<version>`
- [ ] Request includes agent_id and version query parameters
- [ ] Agent does NOT skip the request even though bundled database exists
- [ ] If Worker is deployed: response is 200 with gzip data

---

## E-04: Daemon Mode — Extended Stability [extended-testing]

```bash
slogr-agent daemon --config schedule.json
# Run for 30+ minutes with 60-second interval
```

**Verify:**
- [ ] All measurement cycles complete successfully
- [ ] ASN enrichment works on every traceroute (not just first)
- [ ] No memory leak: check heap usage at 5min and 30min — should be stable
- [ ] No thread leak: thread count stable across cycles
- [ ] Log output remains clean (no accumulating warnings)

Memory check:
```bash
jcmd <pid> GC.heap_info
# Or: jstat -gc <pid> 5000  (sample every 5 seconds)
```
- [ ] Heap used < 200MB (384MB budget, ip2asn adds ~22MB)
- [ ] No continuous heap growth over 30 minutes

---

## E-05: Daemon Mode — ASN Database Refresh [extended-testing]

To test without waiting 24 hours, temporarily modify the check interval in AsnDatabaseUpdater or set metadata `downloaded_at` to 31 days ago:

```bash
# Edit ~/.slogr/ip2asn-meta.json — set downloaded_at to 31 days ago
slogr-agent daemon --config schedule.json
```

**Verify:**
- [ ] Daemon detects stale database and triggers refresh
- [ ] "ASN database refreshed" message in logs
- [ ] SwappableAsnResolver swaps to new database without restart
- [ ] Subsequent traceroutes use the refreshed database
- [ ] No measurement interruption during swap

---

## E-06: TWAMP Multi-Path Validation [extended-testing]

Run check against 3+ different targets across different networks:

```bash
slogr-agent check <target-us-east>
slogr-agent check <target-us-west>
slogr-agent check <target-europe>
```

**Verify:**
- [ ] Each path shows different traceroute hops
- [ ] ASN paths differ (different transit providers)
- [ ] RTT increases with geographic distance (sanity check)
- [ ] SLA grades may differ based on path quality
- [ ] All 3 complete without error

---

## E-07: RabbitMQ Publishing — CONNECTED Mode [extended-testing]

Requires: `SLOGR_API_KEY=sk_live_...` and RabbitMQ reachable.

```bash
slogr-agent daemon --config schedule.json
# Run 2-3 measurement cycles
```

**Verify:**
- [ ] "Starting daemon in CONNECTED mode" in logs
- [ ] RabbitMQ publisher wired successfully
- [ ] Measurements published to exchange `slogr.measurements`
- [ ] Published TWAMP results include rtt_min/avg/max_ms
- [ ] Published traceroute results include hop_asn and hop_asn_name
- [ ] WAL (write-ahead log) entries created and cleared on ACK

---

## E-08: Connect and Disconnect Flow [extended-testing]

```bash
slogr-agent connect --api-key sk_live_...
slogr-agent status
slogr-agent disconnect
slogr-agent status
```

**Verify:**
- [ ] Connect stores encrypted credential in `~/.slogr/credential.enc`
- [ ] Status shows CONNECTED after connect
- [ ] Disconnect removes credential
- [ ] Status shows ANONYMOUS after disconnect
- [ ] No credential plaintext in logs

---

## E-09: Setup-ASN Command [extended-testing]

```bash
slogr-agent setup-asn
slogr-agent setup-asn --db-path /nonexistent/path.mmdb
```

**Verify:**
- [ ] Default path: reports whether `~/.slogr/GeoLite2-ASN.mmdb` exists
- [ ] Custom path: reports file not found for nonexistent path
- [ ] Shows ip2asn automatic enrichment note (v1.0.6)
- [ ] Exit code 0

---

## E-10: JSON Schema Compliance — ClickHouse [extended-testing]

Run `slogr-agent check <target> --format json` and validate against ClickHouse schema.

**twamp_raw columns (Rule T6):**
- [ ] tenant_id, session_id, source_agent_id, dest_agent_id
- [ ] rtt_min_ms, rtt_avg_ms, rtt_max_ms
- [ ] fwd_min_rtt_ms, fwd_avg_rtt_ms, fwd_max_rtt_ms, fwd_jitter_ms, fwd_loss_pct
- [ ] rev_min_rtt_ms, rev_avg_rtt_ms, rev_max_rtt_ms, rev_jitter_ms, rev_loss_pct
- [ ] packets_sent, packets_recv, schema_version

**traceroute_raw columns (Rule T6):**
- [ ] hop_ttl, hop_ip, hop_asn (integer), hop_asn_name (string)
- [ ] hop_rtt_ms, hop_loss_pct
- [ ] schema_version

**Verify no extra fields** that would break the Ingest Bridge parser.

---

## E-11: Edge Cases [extended-testing]

```bash
slogr-agent check 127.0.0.1
slogr-agent check 10.0.0.1
slogr-agent check ::1          # IPv6 loopback (if supported)
```

**Verify:**
- [ ] Localhost: no crash, ASN is null for 127.0.0.1
- [ ] Private IP: fails gracefully, exit code 3, no ASN crash
- [ ] IPv6: ASN returns null (ip2asn-v4 only), no crash

Kill agent mid-measurement:
```bash
slogr-agent daemon --config schedule.json &
sleep 5
kill -9 $!
# Restart
slogr-agent daemon --config schedule.json
```
- [ ] Restart works cleanly
- [ ] No corrupt ip2asn cache files
- [ ] No corrupt WAL files
- [ ] No corrupt credential files

---

## E-12: SLA Profile Coverage [extended-testing]

Run measurements across different SLA profiles (configure in schedule.json):

**Verify for each profile (VoIP, Gaming, Streaming, General):**
- [ ] Thresholds evaluated correctly (GREEN/YELLOW/RED)
- [ ] RTT thresholds differ per profile (VoIP is stricter than Streaming)
- [ ] Jitter thresholds apply correctly
- [ ] Loss thresholds apply correctly

---

## E-13: Concurrent Check Commands [extended-testing]

Run multiple check commands simultaneously:

```bash
slogr-agent check <target-1> &
slogr-agent check <target-2> &
wait
```

**Verify:**
- [ ] Both complete without error
- [ ] No port binding conflicts (check uses startReflector=false)
- [ ] No shared state corruption
- [ ] Both produce valid results

---

## E-14: Large Traceroute Path [extended-testing]

Target a distant endpoint that produces 15+ hops:

```bash
slogr-agent check <distant-target>
```

**Verify:**
- [ ] All hops up to max (30) are captured
- [ ] ASN enrichment works for all public hops in a long path
- [ ] No timeout on traceroute phase
- [ ] Multiple ASN transitions visible in the path

---

## E-15: DSCP / QoS Marking — Per Profile [extended-testing]

Run check with different SLA profiles and verify DSCP is set on outgoing packets.
Use `tcpdump` or Wireshark on the sender to capture TWAMP UDP packets and inspect the IP ToS byte.

```bash
# Capture on sender host (run in parallel with check command)
tcpdump -i eth0 -v udp port 862 -c 20 &
slogr-agent check <target> --profile voip
```

**Verify per profile (ToS byte = DSCP << 2):**

| Profile | DSCP | ToS Byte (hex) | Verify |
|---------|------|----------------|--------|
| voip | 46 (EF) | 0xB8 | [ ] |
| gaming | 34 (AF41) | 0x88 | [ ] |
| streaming | 36 (AF42) | 0x90 | [ ] |
| internet | 8 (CS1) | 0x20 | [ ] |
| iot | 0 (BE) | 0x00 | [ ] |
| cloud-gaming | 40 (CS5) | 0xA0 | [ ] |

- [ ] ToS byte in captured packets matches expected value for each profile
- [ ] DSCP marking is applied on BOTH Windows (JavaUdpTransport) and Linux (JNI)
- [ ] DSCP does not affect measurement results (RTT/jitter should be similar across profiles on same path)

---

## E-16: SLA Profile — All 24 Profiles [extended-testing]

Run check command with every built-in SLA profile and verify no crash, valid output:

```bash
for profile in voip gaming streaming pcoip internet videoconf download upload \
  player cloud-saas online-payment ssm mail remote-desktop online-learning \
  largefile iot multigame broadcasting cloud-gaming vpn webconf data-recovery music; do
  echo "=== $profile ==="
  slogr-agent check <target> --profile $profile --format json
done
```

**Verify for each profile:**
- [ ] No crash or error
- [ ] Grade is computed (GREEN/YELLOW/RED)
- [ ] packets_sent matches the profile's n_packets value
- [ ] Stricter profiles (voip: rtt_green=30ms) grade RED on high-latency paths where lenient profiles (largefile: rtt_green=200ms) grade GREEN
- [ ] Invalid profile name → error message, exit code 2

---

## E-17: SLA Profile — Timing Modes [extended-testing]

Test FIXED_INTERVAL vs POISSON timing modes. The `voip` profile uses POISSON by default.

```bash
slogr-agent check <target> --profile voip --format json    # POISSON
slogr-agent check <target> --profile internet --format json # FIXED
```

**Verify:**
- [ ] Both complete successfully
- [ ] POISSON mode: packet inter-arrival times vary (capture with tcpdump, check timestamps)
- [ ] FIXED mode: packet inter-arrival times are uniform (within ~1ms jitter)
- [ ] Both produce valid RTT/jitter/loss measurements

---

## E-18: TWAMP Custom Port [extended-testing]

```bash
# Start reflector on non-standard port
slogr-agent daemon --config schedule-port-8862.json  # targetPort: 8862

# Check against custom port
slogr-agent check <target> --port 8862
```

**Verify:**
- [ ] Reflector binds on custom port
- [ ] Check connects to custom port successfully
- [ ] Measurement results are valid
- [ ] Default port (862) still works in separate test

---

## E-19: TWAMP Fixed Test Port (SLOGR_TEST_PORT) [extended-testing]

```bash
export SLOGR_TEST_PORT=863
slogr-agent daemon --config schedule.json
```

**Verify:**
- [ ] Logs show test sessions binding to port 863 (not ephemeral)
- [ ] SO_REUSEPORT applied (multiple concurrent sessions share port)
- [ ] GCP firewall only needs UDP 863 (not full ephemeral range)
- [ ] Unset SLOGR_TEST_PORT → falls back to ephemeral ports

---

## E-20: Agent State Transitions [extended-testing]

Walk through all three agent states:

```bash
# State 1: ANONYMOUS (no key)
unset SLOGR_API_KEY
slogr-agent status   # → ANONYMOUS
slogr-agent daemon &  # → "Starting daemon in ANONYMOUS mode (stdout only)"

# State 2: REGISTERED (free key)
export SLOGR_API_KEY=sk_free_test123
slogr-agent status   # → REGISTERED

# State 3: CONNECTED (live key)
export SLOGR_API_KEY=sk_live_test123
slogr-agent status   # → CONNECTED
```

**Verify:**
- [ ] Each state prints correct mode in status
- [ ] ANONYMOUS: no OTLP export, no RabbitMQ, nudge message logged
- [ ] REGISTERED: OTLP export enabled (if endpoint set), no RabbitMQ
- [ ] CONNECTED: OTLP + RabbitMQ + Pub/Sub
- [ ] Invalid key (no prefix): falls to ANONYMOUS

---

## E-21: OTLP Metrics Export [extended-testing]

Requires: SLOGR_OTLP_ENDPOINT set to an OTLP collector (or mock endpoint like `https://httpbin.org/post` for inspection).

```bash
export SLOGR_API_KEY=sk_free_test123
export SLOGR_OTLP_ENDPOINT=http://localhost:4318
slogr-agent check <target>
```

**Verify (inspect at collector):**
- [ ] Metrics exported with prefix `slogr.network.*`
- [ ] `slogr.network.rtt.avg` present with correct value
- [ ] `slogr.network.jitter.forward` present
- [ ] `slogr.network.loss.forward` present
- [ ] `slogr.network.packets.sent/received` present
- [ ] `slogr.network.sla.grade` present (ordinal: 0=GREEN, 1=YELLOW, 2=RED)
- [ ] Attributes include: agent_id, profile, measurement_method, service.name, service.version
- [ ] ANONYMOUS mode: OTLP skipped, nudge message logged once

---

## E-22: WAL (Write-Ahead Log) Lifecycle [extended-testing]

Requires: CONNECTED mode with RabbitMQ reachable.

```bash
export SLOGR_API_KEY=sk_live_...
slogr-agent daemon --config schedule.json
# Run 3 measurement cycles, then kill RabbitMQ (or block network)
# Wait for 2 more cycles (entries buffered in WAL)
# Restore RabbitMQ
# Wait for replay
```

**Verify:**
- [ ] `~/.slogr/wal.ndjson` created with buffered entries
- [ ] Each entry is valid NDJSON (one JSON object per line)
- [ ] On RabbitMQ reconnect: WAL entries replayed and published
- [ ] `~/.slogr/wal.acked` tracks acknowledged entries
- [ ] After replay: compact() removes acked entries
- [ ] WAL size stays under 100,000 rows (evicts oldest if exceeded)

---

## E-23: Doctor Command — Full Diagnostics [extended-testing]

```bash
slogr-agent doctor
```

**Verify on Linux:**
- [ ] JNI native library check: PASS (libslogr-native.so found)
- [ ] CAP_NET_RAW check: reports capability status
- [ ] TLS connectivity: handshake to slogr.io:443 succeeds (or skipped if air-gapped)
- [ ] Exit code 0 if all pass, 1 if any fail
- [ ] No secrets or sensitive info in output

**Verify on Windows:**
- [ ] JNI check: SKIP or FAIL with helpful message (no .so on Windows)
- [ ] CAP_NET_RAW: SKIP (Linux only)
- [ ] TLS connectivity: should still work
- [ ] Exit code reflects results

---

## E-24: Schedule Config Validation [extended-testing]

Test daemon with various schedule configurations:

```bash
# Valid schedule — 2 sessions
slogr-agent daemon --config schedule-valid.json

# Corrupt JSON
slogr-agent daemon --config schedule-corrupt.json

# Missing file
slogr-agent daemon --config /nonexistent.json

# No --config flag (uses persisted schedule)
slogr-agent daemon
```

**Verify:**
- [ ] Valid config: loads sessions, reports count
- [ ] Corrupt JSON: warns, falls back to persisted schedule
- [ ] Missing file: warns, falls back to persisted schedule
- [ ] No flag: loads from `~/.slogr/schedule.json` if exists, else responder-only mode

---

## E-25: Traceroute Mode Forcing [extended-testing]

If `--traceroute-mode` flag exists (or via schedule config `traceroute_mode`):

```bash
slogr-agent check <target> --traceroute              # Auto-fallback (ICMP → TCP → UDP)
slogr-agent check <target> --traceroute-mode icmp     # ICMP only
slogr-agent check <target> --traceroute-mode tcp      # TCP only
```

**Verify:**
- [ ] Auto mode: runs ICMP first, may fallback
- [ ] Forced ICMP: only ICMP probes sent (verify with tcpdump)
- [ ] Forced TCP: only TCP SYN probes to port 443
- [ ] Forced mode does NOT trigger fallback chain even if mostly stars
- [ ] If flag doesn't exist yet: document as missing feature for v1.0.7

---

## E-26: Packet Size Variations [extended-testing]

Different profiles use different packet sizes. Verify with tcpdump:

```bash
tcpdump -i eth0 udp port 862 -c 10 &
slogr-agent check <target> --profile voip       # smaller packets
slogr-agent check <target> --profile largefile   # larger packets
```

**Verify:**
- [ ] UDP packet size matches profile's `packet_size` field
- [ ] Smaller packets (voip) have lower overhead
- [ ] Larger packets don't cause fragmentation issues on standard MTU (1500)

---

## E-27: Credential Store Security [extended-testing]

```bash
slogr-agent connect --api-key sk_live_test123
# Inspect credential file
xxd ~/.slogr/credential.enc | head
```

**Verify:**
- [ ] File is binary (AES-256-GCM encrypted), not plaintext
- [ ] First 12 bytes are random IV (different on each store)
- [ ] No API key visible in the file
- [ ] `grep -r "sk_live" ~/.slogr/` returns nothing (no plaintext keys anywhere)
- [ ] Credential survives agent restart (load and use without re-entering key)
- [ ] Moving credential.enc to another machine fails (key derived from machine fingerprint)

---

## E-28: Check Command — All CLI Flags Combined [extended-testing]

```bash
slogr-agent check <target> --port 862 --profile voip --format json --traceroute
```

**Verify:**
- [ ] All flags accepted together without conflict
- [ ] JSON output includes both TWAMP and traceroute sections
- [ ] Profile-specific DSCP and thresholds applied
- [ ] Custom port used for connection

---

## Results Template

**Regression tests** (R-01 through R-15) run on every build:

| Test | Tag | Windows | Linux | Notes |
|------|-----|---------|-------|-------|
| R-01 | regression | | | |
| R-02 | regression | | | |
| R-03 | regression | | | |
| R-04 | regression | | | |
| R-05 | regression | | | |
| R-06 | regression | | | |
| R-07 | regression | | | |
| R-08 | regression | | | |
| R-09 | regression | | | |
| R-10 | regression | | | |
| R-11 | regression | | | |
| R-12 | regression | | | |
| R-13 | regression | | | |
| R-14 | regression | | | |
| R-15 | regression | | | |

**Extended tests** (E-01 through E-28) run on minor/major releases:

| Test | Tag | Windows | Linux | Notes |
|------|-----|---------|-------|-------|
| E-01 | extended | | | |
| E-02 | extended | | | |
| E-03 | extended | | | |
| E-04 | extended | | | |
| E-05 | extended | | | |
| E-06 | extended | | | |
| E-07 | extended | | | |
| E-08 | extended | | | |
| E-09 | extended | | | |
| E-10 | extended | | | |
| E-11 | extended | | | |
| E-12 | extended | | | |
| E-13 | extended | | | |
| E-14 | extended | | | |
| E-15 | extended | | | |
| E-16 | extended | | | |
| E-17 | extended | | | |
| E-18 | extended | | | |
| E-19 | extended | | | |
| E-20 | extended | | | |
| E-21 | extended | | | |
| E-22 | extended | | | |
| E-23 | extended | | | |
| E-24 | extended | | | |
| E-25 | extended | | | |
| E-26 | extended | | | |
| E-27 | extended | | | |
| E-28 | extended | | | |

| Test | Tag | Windows | Linux | Notes |
|------|-----|---------|-------|-------|
| R-01 | regression | | | |
| R-02 | regression | | | |
| R-03 | regression | | | |
| R-04 | regression | | | |
| R-05 | regression | | | |
| R-06 | regression | | | |
| R-07 | regression | | | |
| R-08 | regression | | | |
| R-09 | regression | | | |
| R-10 | regression | | | |
| R-11 | regression | | | |
| R-12 | regression | | | |
| R-13 | regression | | | |
| R-14 | regression | | | |
| R-15 | regression | | | |
| E-01 | extended | | | |
| E-02 | extended | | | |
| E-03 | extended | | | |
| E-04 | extended | | | |
| E-05 | extended | | | |
| E-06 | extended | | | |
| E-07 | extended | | | |
| E-08 | extended | | | |
| E-09 | extended | | | |
| E-10 | extended | | | |
| E-11 | extended | | | |
| E-12 | extended | | | |
| E-13 | extended | | | |
| E-14 | extended | | | |
