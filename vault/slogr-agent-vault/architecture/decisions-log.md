---
status: locked
version: 1.0
depends-on: []
claude-code-context:
  - "Read when questioning why a decision was made"
  - "Do not override these decisions without explicit approval"
---

# Decisions Log

## ADR-001: Language — Kotlin/JVM

**Decision:** Kotlin targeting JVM.

**Rationale:** The Java agent is the production reference. Kotlin is 100% interoperable with Java — the JNI library, proto files, and Java classes can be called directly. The Java agent was studied for RFC 5357 protocol understanding. Future: Kotlin Multiplatform compiles to Native (iOS, standalone) and JS (Chrome extension). Claude Code handles all development, removing expertise concerns.

**Rejected:** Fix the Python agent (would require rewriting 80% and still wouldn't match backend contracts). Pure Java (can't target iOS/browser). Rust (no access to previous Rust code, would be a from-scratch rewrite with no Java interop).

## ADR-002: Keep JNI C Library (twampUdp.c)

**Decision:** Implement `twampUdp.c` as a JNI C library for raw UDP socket operations, called from Kotlin.

**Rationale:** The JNI layer handles raw POSIX UDP sockets with `recvmsg()`, ancillary data for TTL capture (`IP_RECVTTL`), DSCP/TOS setting, and IPv6 support. Rewriting this in pure Kotlin/JVM is not possible — `java.net.DatagramSocket` doesn't expose TTL capture or ancillary data. The C code handles the low-level socket operations that the JVM cannot access natively.

## ADR-003: Extend JNI with Native Traceroute

**Decision:** Add traceroute probe functions (`traceroute.c`) to the same JNI library instead of wrapping the OS `traceroute` command.

**Rationale:** Wrapping OS `traceroute` is fragile — different output formats across Linux distros, macOS, Windows. Requires parsing text with regex. TCP traceroute mode requires `CAP_NET_RAW` regardless. Since we already have JNI infrastructure and need `CAP_NET_RAW` for TWAMP's DSCP/TOS, adding ICMP/UDP probe sockets in C is a natural extension. Eliminates cross-platform parsing problems and produces structured results directly.

**Rejected:** Wrapping OS traceroute (fragile parsing, different output formats). Embedding a separate Go/Rust binary (adds a third language). Pure Kotlin/JVM ICMP (not possible without raw sockets).

## ADR-004: RabbitMQ JWT Auth (Not Per-Agent Users)

**Decision:** Use the RabbitMQ OAuth 2.0 / JWT Auth Plugin. The BFF signs a JWT with routing key scopes at registration time. RabbitMQ validates the signature against a public key. No per-agent users on the broker.

**Rationale:** Creating 100K+ individual RabbitMQ users bloats Mnesia (RabbitMQ's internal database) and degrades cluster performance during replication and recovery. JWT auth adds zero state to the broker. Credential rotation is automatic (short-lived JWTs with refresh).

**Rejected:** Per-agent RabbitMQ users (Mnesia scaling problem). Static shared credentials (compromise one = compromise all). Per-tenant users (still thousands of users).

## ADR-005: AWS Marketplace — AMI with ASG-of-1

**Decision:** AMI-based listing with CloudFormation. ASG of size 1 referencing an SSM parameter for the latest AMI ID. Enables immutable infrastructure updates.

**Rationale:** The agent is one process on one instance. Container-based listings (ECS/EKS) add friction — customer needs container orchestration for one process. ASG-of-1 + SSM parameter allows Slogr to publish new AMI versions; customers opt-in to updates by cycling the ASG. Helm chart as R2 deliverable for enterprise teams requiring K8s.

## ADR-006: Mesh Agents = Same Binary

**Decision:** No behavioral difference between mesh agents (tenant 00001) and customer agents. Same binary, same code. Only `tenant_id` differs.

**Rationale:** One binary = one test matrix, one upgrade path, one AMI. All mesh-vs-customer access control (read-only badges, 403 on commands from wrong tenant) is enforced by the BFF. RBAC on the broker is enforced via JWT scope — each agent's JWT only permits publishing to `agent.{its_own_agent_id}.*`.

## ADR-007: Local MaxMind ASN Database (No Live Queries)

**Decision:** Use MaxMind GeoLite2-ASN MMDB file for IP-to-ASN resolution. No live TCP queries to Team Cymru or any other external service.

**Rationale:** Live queries add latency (TCP connection + query per batch), create an external dependency, and don't work offline. MaxMind GeoLite2-ASN is ~7 MB, free with attribution, and provides sub-millisecond lookups. The database is optional — traceroute works without it (shows IPs only, no ASN names). Connected agents can auto-download at registration. Free agents use `slogr-agent setup-asn`.

## ADR-008: JNI Library Loading — No /tmp Extraction

**Decision:** Native library extracted to a configurable directory (default `/opt/slogr/lib/`), never to `/tmp`. Override with `SLOGR_NATIVE_DIR` env var or `-Dslogr.native.dir=`.

**Rationale:** Enterprise environments mount `/tmp` with `noexec` (CIS benchmarks, SELinux). JNI libraries extracted there fail with `UnsatisfiedLinkError`. The AMI and Docker image pre-install the `.so` so extraction isn't needed in production.

## ADR-009: TWAMP Interop with Third-Party Devices

**Decision:** The agent's TWAMP implementation must be fully RFC 5357 compliant and interoperate with any standards-compliant TWAMP reflector (Cisco IOS, Juniper JUNOS, Nokia, etc.) and accept sessions from any compliant controller.

**Rationale:** Enterprise customers measure paths between Slogr agents AND to/from their existing network infrastructure. A TWAMP implementation that only works with itself has limited value. The Java agent was designed for carrier-grade environments with mixed vendor equipment, confirming RFC compliance is achievable.

## ADR-010: Authenticated TWAMP Mode

**Decision:** Support unauthenticated and authenticated modes. Encrypted mode deferred.

**Rationale:** Enterprise routers often require authenticated TWAMP sessions. The Java agent has `KeyChain`, `KeyStore`, and `TwampControlSessionKey` classes suggesting auth mode support. Claude Code should investigate the Java implementation first, then port whatever exists. Encrypted mode adds complexity (AES-CBC per packet) and can come later.

## ADR-011: Two Agent States (Not Modes)

**Decision:** The agent has two states — disconnected (free) and connected (Pro/$10 month) — not modes. The state is determined by whether credentials exist on disk, not by CLI flags.

**Rationale:** This supports the PLG funnel. A developer installs the free agent, uses it for months, then runs `slogr-agent connect` when they need SaaS features. No reinstall, no config change, no binary difference. The Enterprise path (via Slogr Proxy) keeps agents disconnected — they export OTLP to the proxy without knowing the SaaS exists.

## ADR-012: OTLP/HTTP (Not gRPC)

**Decision:** OTLP over HTTP for metric export, not OTLP/gRPC.

**Rationale:** Simpler, works through HTTP proxies (important for enterprise networks with restrictive egress), fewer dependencies (HTTP client vs gRPC client). The Slogr Proxy listens on standard OTLP port 4318 (HTTP). Most OTel collectors support both; HTTP is the lower-friction option.
