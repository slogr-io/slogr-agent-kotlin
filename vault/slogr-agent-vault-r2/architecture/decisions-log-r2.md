# R2 Decisions Log

Extends R1 `architecture/decisions-log.md`. ADRs 001-020 remain locked. R2 adds ADR-021 through ADR-032.

---

## ADR-021: Thread Pool Reflector (replaces thread-per-session)

**Status:** Locked
**Context:** R1 uses thread-per-session for the TWAMP reflector. At 10,000+ concurrent inbound sessions, this means 10,000 threads × ~1MB stack = 10GB RAM. JVM thread limits and context-switching overhead make this unscalable for mesh agents receiving traffic from large fleets.
**Decision:** Thread pool sized to `Runtime.availableProcessors() * 2`. Per-session UDP sockets preserved (no shared DatagramChannel). T2 captured at kernel level via JNI `recvmsg()` with `SO_TIMESTAMPING`. Session state in `ConcurrentHashMap<SessionId, ReflectorSession>`. Pre-allocated `ByteBuffer` pool.
**Consequence:** Mesh agent on a t3.micro (2 cores) handles ~100 sessions. c5.4xlarge (16 cores) handles ~10,000. Same binary, no config change.

## ADR-022: API Key Registration (replaces bootstrap tokens)

**Status:** Locked
**Context:** R1 uses single-use bootstrap tokens (24h expiry) generated in the SaaS UI. This requires per-agent token generation which doesn't scale to mass deployment of 200+ agents.
**Decision:** One env var: `SLOGR_API_KEY`. Accepts `sk_free_*` (OTLP only) and `sk_live_*` (full SaaS). One endpoint: `POST /v1/agents`. Key prefix determines capabilities. Machine fingerprint (`SHA256(mac + hostname)`) enables reconnect with same agent_id. One key per tenant for mass deployment.
**Consequence:** `BootstrapRegistrar.kt` from R1 replaced by `ApiKeyRegistrar.kt`. Same `ConnectCommand.kt` interface.

## ADR-023: Three-State Agent Model

**Status:** Locked
**Context:** R1 has two states: disconnected and connected. R2 needs a middle state for users who register for OTLP export but don't pay for full SaaS.
**Decision:** Three states — Anonymous (no key, stdout only), Registered (sk_free_*, OTLP enabled), Connected (sk_live_*, full SaaS). State determined by key prefix at startup. Zero-reinstall upgrade: user adds env var, agent transitions without restart.
**Consequence:** `DaemonCommand.kt` checks `SLOGR_API_KEY` on startup and logs which mode it's in.

## ADR-024: OTLP Export Gate

**Status:** Locked
**Context:** OTLP export should require registration so Slogr can track who uses the agent for lead qualification. But daemon mode (stdout only) should remain free and unregistered.
**Decision:** `SLOGR_API_KEY` required for OTLP export. Daemon stdout mode: no key needed. `check` one-shot: no key needed. Free key (`sk_free_*`) obtained via LinkedIn OAuth at slogr.io/keys.
**Consequence:** `OtlpExporter.kt` checks for key. If absent, logs nudge message and skips export.

## ADR-025: Free Key Validation on Startup

**Status:** Locked
**Context:** `sk_free_*` keys don't trigger `/v1/agents` registration. If the key is revoked, the agent wouldn't know — OTLP exports would silently fail at the collector.
**Decision:** On daemon startup with `sk_free_*`: one-time `GET /v1/keys/validate`. 200 OK → cache locally for 24h, start REGISTERED. 401 → log warning, start ANONYMOUS. Network error → trust format, start REGISTERED (supports air-gapped environments).
**Consequence:** New endpoint `GET /v1/keys/validate` needed in Layer 3 BFF. New cache file at `/var/lib/slogr/key_validation.json`.

## ADR-026: Virtual Clock Estimation

**Status:** Locked
**Context:** One-way delay (T2-T1, T4-T3) requires NTP sync between sender and reflector. Not always available. Industry solution: Cisco Accedian "Virtual Clock per Responder."
**Decision:** Estimate clock offset per responder: `offset = ((T2-T1) + (T3-T4)) / 2`. Take minimum RTT sample as best estimate. Three-tier: SYNCED (both NTP), ESTIMATED (virtual clock), UNSYNCABLE (fall back to RTT/2). Add `clock_sync_status` enum and `estimated_clock_offset_ms` to MeasurementResult.
**Consequence:** One-way jitter (IPDV) always accurate regardless of sync. One-way delay values come with a quality indicator.

## ADR-027: Air-Gapped Detection

**Status:** Locked
**Context:** Telco and enterprise environments often have no internet access. Agent should detect this and adjust its messaging.
**Decision:** Try DNS resolve of `slogr.io` with 3-second timeout. If fails → air-gapped. CLI footer: `slogr.io/enterprise` instead of `slogr.io`.
**Consequence:** New `AirGapDetector.kt`. Footer text in `TextResultFormatter.kt` is conditional.

## ADR-028: CLI Footer Nudge

**Status:** Locked
**Context:** Free CLI users need a path to discover the SaaS. The agent output is the only touchpoint.
**Decision:** `check` text output ends with `→ For historical results and root cause analysis: https://slogr.io`. Air-gapped variant: `→ Enterprise deployment? Contact us at https://slogr.io/enterprise`. Not on JSON output. Not on daemon mode. Not on OTLP export.
**Consequence:** `TextResultFormatter.kt` appends footer. `AirGapDetector.kt` determines which variant.

## ADR-029: Symmetric Path Scheduling

**Status:** Locked
**Context:** Bidirectional traceroute requires both agents to trace toward each other. If A→B exists, B→A must also exist.
**Decision:** Layer 3 BFF enforces: every `set_schedule` that creates a path A→B automatically creates B→A. Deletion also deletes both directions. The agent doesn't know about pairing — it runs whatever schedule it receives. Layer 2.5 just delivers commands.
**Consequence:** No agent code change. BFF constraint only. `paths` table in Layer 3 Cloud SQL stores pair relationship.

## ADR-030: Push Config Extended Payload

**Status:** Locked
**Context:** R1 `push_config` has 4 fields. R2 traceroute needs configurable heartbeat interval, forced refresh, probes per hop, and fallback modes.
**Decision:** 8 fields total, all optional, partial-apply semantics. New fields: `traceroute_probes_per_hop`, `traceroute_heartbeat_interval_cycles`, `traceroute_forced_refresh_hours`, `traceroute_fallback_modes`.
**Consequence:** `PushConfigHandler.kt` updated. Agent applies only present fields, keeps existing values for absent fields.

## ADR-031: Daemon Auto-Connect

**Status:** Locked
**Context:** Mass deployment (Docker, K8s, SCCM) needs agents to connect without interactive `slogr-agent connect` step.
**Decision:** If `SLOGR_API_KEY` env var is set and no stored credential exists, daemon auto-registers on startup. Always loud: first log line states mode and reason. `slogr-agent connect` remains as the interactive path.
**Consequence:** `DaemonCommand.kt` checks for key + credential on startup.

## ADR-032: Egress Optimization (Traceroute)

**Status:** Locked
**Context:** 200+ agents × 200+ paths × every 5 minutes = 40,000+ traceroute snapshots per window. Most are unchanged. ~11.5 GB/day of redundant data.
**Decision:** Three-tier publish: (1) change-only by default — only publish when ASN path differs from previous. (2) Heartbeat every N unchanged cycles (default 6 = every 30 min). (3) Forced refresh every N hours (default 6). Layer 2 uses heartbeat absence (>35 min) to detect broken agent vs stable path.
**Consequence:** ~80% egress reduction. ~$750/month savings at 200+ agents. Configurable via `push_config`.

## ADR-033: JNI SO_TIMESTAMPING Fallback

**Status:** Locked
**Context:** Not all cloud hypervisors support kernel timestamps via `recvmsg()`. Azure VMs, older AWS Nitro, VMware guests may return empty `CMSG_DATA`.
**Decision:** If kernel timestamp unavailable, fallback to `clock_gettime(CLOCK_REALTIME)`. Add `timestamp_source` flag (KERNEL=1, USERSPACE=2) to JNI return struct. Propagate to Kotlin. `ClockSyncDetector` never reports SYNCED when source is USERSPACE.
**Consequence:** T2 precision degrades from microseconds to milliseconds on affected platforms. Virtual clock estimator widens tolerance accordingly.

## ADR-034: File-Backed Persistent Fingerprint

**Status:** Locked
**Context:** `SHA256(mac + hostname)` computed dynamically fails in K8s DaemonSets, VMware clones, VDI pools, and Docker containers.
**Decision:** Generate fingerprint once on first boot, include a UUID for clone divergence, write to `/var/lib/slogr/.agent_fingerprint`. Read from file on all subsequent boots. Only regenerate if file doesn't exist.
**Consequence:** Cloned VMs get unique identities after first independent boot. Docker containers need the fingerprint file on a mounted volume for persistence.

## ADR-035: Bounded WAL Eviction

**Status:** Locked
**Context:** WAL without a size limit can fill the host's disk during extended network outages.
**Decision:** Hard cap: 500MB size OR 72 hours age (whichever is hit first). Oldest entries silently evicted. Configurable via `push_config` (`wal_max_size_mb`, `wal_max_age_hours`).
**Consequence:** During multi-day outages, oldest telemetry is lost. Acceptable trade-off vs killing the host.

## ADR-036: Probe Mode Classification

**Status:** Locked
**Context:** ICMP blocked + TCP working = path is healthy but ICMP-filtered. Reporting "100% loss" is misleading.
**Decision:** Add `probe_mode` enum: ICMP_AND_TCP, TCP_ONLY, ICMP_ONLY, BOTH_FAILED. Display "ICMP filtered (TCP healthy)" instead of "100% loss" when `probe_mode = TCP_ONLY`.
**Consequence:** Layer 2 Detection Worker and Layer 3 Explanation Engine must check `probe_mode` before triggering loss alerts.

## ADR-037: Local Prometheus Exporter

**Status:** Locked
**Context:** SREs want immediate local Prometheus scraping before configuring OTLP pipelines.
**Decision:** Embedded HTTP server on `127.0.0.1:9090/metrics`. Prometheus exposition format. Always available in daemon mode. NOT gated behind `sk_free_*` key. Binds to localhost only.
**Consequence:** SREs can `curl localhost:9090/metrics` immediately after install. Zero config. Zero keys.

## ADR-038: Doctor Command

**Status:** Locked
**Context:** "It doesn't work" is the most common support ticket.
**Decision:** `slogr-agent doctor` command that verifies: JNI loading, CAP_NET_RAW, CAP_NET_BIND_SERVICE, ICMP functionality, DNS resolution, TLS connectivity, API key validity, RabbitMQ reachability, Pub/Sub status, disk space, WAL status. Each failed check includes specific remediation instructions.
**Consequence:** First support response: "Please run `slogr-agent doctor` and share the output."

## ADR-039: Kill Switch (HaltMeasurement)

**Status:** Locked
**Context:** Misconfigured schedule could accidentally flood a target. Need instant remote stop.
**Decision:** New command type `halt_measurement`. Immediately stops all sessions, purges in-memory schedule, agent stays connected and responsive. 30-second timeout. Resume via new `set_schedule`.
**Consequence:** 6th command type added to Layer 2.5 command payloads. Admin Console gets "Emergency Stop" button.
