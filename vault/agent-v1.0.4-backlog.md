# Slogr Agent — v1.0.4 Backlog
Date: April 7 2026
Status: Scheduled
Branch: cut from master after v1.0.3

## Root cause from v1.0.3 E2E (April 7 2026)

v1.0.3 diagnostic output confirmed: TCP:862
handshake completes successfully (no WARN from
MeasurementEngineImpl, result.error is null),
but 0 UDP test packets are received. The reflector
negotiates an ephemeral UDP port via AcceptTwSession,
and GCP firewall blocks it.

Workaround: GCP firewall rule allowing UDP 1024-65535
between slogr-agent tagged instances (Option A).

v1.0.4 implements the proper fix (Option B): a fixed,
predictable UDP test port so only one port needs to
be opened, not a 64K range.

---

### FEAT-1 [HIGH]: Fixed TWAMP test port

Files:
  engine/.../twamp/responder/TwampSessionReflector.kt
  engine/.../twamp/responder/TwampResponderSession.kt
  engine/.../twamp/responder/TwampReflector.kt
  engine/.../MeasurementEngineImpl.kt
  platform/.../config/AgentConfig.kt
  platform/.../cli/DaemonCommand.kt

Problem:
  TwampSessionReflector binds an ephemeral UDP port
  (port=0) for each test session. The reflector
  reports this port to the controller via
  AcceptTwSession.port. The controller's sender then
  targets that ephemeral port.

  This works when both agents are on the same
  network or when all UDP ports are open. It fails
  behind any firewall that only allows specific
  ports (GCP, AWS security groups, corporate
  firewalls).

  The current flow:
    Controller ──TCP:862──► Reflector
    Reflector allocates UDP:random (e.g. 48291)
    Reflector ──AcceptTwSession(port=48291)──► Controller
    Controller ──UDP:48291──► Reflector  ← BLOCKED

Fix:
  Add a fixed test port option. When configured,
  all test session reflectors bind to the same
  known port instead of ephemeral.

  1. Add `testPort: Int = 0` to TwampReflector
     constructor. 0 = ephemeral (current behavior).
     Non-zero = all sessions share this port.

  2. Thread testPort through:
     TwampReflector → TwampResponderSession →
     TwampSessionReflector constructor.

  3. TwampSessionReflector: when testPort > 0,
     bind to testPort with SO_REUSEPORT instead
     of port 0.

  4. Add `testPort: Int = 863` to AgentConfig.
     Default to 863 (one above control port).
     Read from SLOGR_TEST_PORT env var.

  5. DaemonCommand: pass config.testPort through
     to MeasurementEngineImpl constructor.

  6. MeasurementEngineImpl: pass testPort to
     TwampReflector constructor.

  Result:
    Controller ──TCP:862──► Reflector
    Reflector ──AcceptTwSession(port=863)──► Controller
    Controller ──UDP:863──► Reflector  ← ONE PORT RULE

  Firewall rule becomes:
    allow TCP+UDP 862-863 from slogr-agent

Acceptance tests:
  1. Daemon with SLOGR_TEST_PORT=863. Remote check
     connects, completes measurement. Verify
     AcceptTwSession contains port 863.
  2. Daemon with SLOGR_TEST_PORT=0 (or unset).
     Verify ephemeral behavior unchanged.
  3. Multiple concurrent sessions with fixed port.
     Verify SO_REUSEPORT allows sharing.
  4. Check from remote agent behind firewall with
     only TCP+UDP 862-863 open. Verify packets
     received > 0.

---

## Carried forward from v1.0.3

### FIX-2 [LOW]: SLOGR_SCHEDULE_PATH env var in wrapper script

(Unchanged from v1.0.3 backlog FIX-4)

File: deploy/wrapper.sh

Fix: Check SLOGR_SCHEDULE_PATH env var, pass
--config flag to daemon command.

### FIX-3 [LOW]: Remove duplicate engine.start() comment in DaemonCommand

(Unchanged from v1.0.3 backlog FIX-5)

File: platform/.../cli/DaemonCommand.kt line 129.
The comment references a duplicate that was already
removed. The comment itself is stale.

---

## Build Order for v1.0.4

1. FEAT-1 first — fixed test port. Changes to
   TwampSessionReflector, TwampResponderSession,
   TwampReflector, MeasurementEngineImpl,
   AgentConfig, DaemonCommand.
2. FIX-2 — wrapper script. Independent.
3. FIX-3 — stale comment removal. Trivial.

Run ./gradlew test after each fix.
All tests must pass before tagging.
