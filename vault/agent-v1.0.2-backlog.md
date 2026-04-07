# Slogr Agent — v1.0.2 Backlog
Date: April 7 2026
Status: Scheduled — build after E2E #2 passes
Branch: cut from master after v1.0.1 tag

## What is in v1.0.1 (current)
- RabbitMqPublisher wired into DaemonCommand
  result callback. TWAMP/health/traceroute results
  publish to slogr.measurements exchange with
  routing key agent.{id}.{type}. Commit 42752c6.
- 1A hardening — TWAMP amplification fix
- 1B hardening — connection limits (3/IP public,
  10/IP mesh), rate limiting, deregister HMAC token,
  PacketBufferPool hard ceiling
- Non-root Dockerfile
- 571 tests passing

## What goes into v1.0.2

### FIX-1 [HIGH]: B2 — Reflector optional in MeasurementEngineImpl

File: engine/src/main/kotlin/io/slogr/agent/engine/MeasurementEngineImpl.kt

Problem:
  The init block unconditionally starts the
  reflector and wires the controller port to
  reflector.actualPort. There is no way to
  construct a MeasurementEngineImpl without
  binding a reflector. When check command runs
  on a VM that already has the daemon running,
  the reflector bind fails (port 862 in use),
  the controller gets wired to the wrong port,
  and the NIO handshake stalls. The check command
  hangs until killed.

Root cause (from AG analysis April 7 2026):
  MeasurementEngineImpl.kt:67-76 — init block
  always calls reflector.start(). TwampReflector
  catches BindException, exits run(), leaves
  serverChannel null. actualPort returns default
  862. Controller connects but handshake stalls.

Fix:
  Add constructor parameter:
    startReflector: Boolean = true

  In init block:
    if (startReflector) {
      reflector.start()
      // wait for bind
      controller = TwampController(
        adapter = adapter,
        port = reflector.actualPort,
        ...
      )
    } else {
      controller = TwampController(
        adapter = adapter,
        port = config.defaultTwampPort,
        ...
      )
    }

  Update call sites:
    DaemonCommand.kt — keep default (startReflector = true)
    CheckCommand.kt  — set startReflector = false

  This avoids wasting a thread and port for
  client-only operations.

Acceptance test:
  Run check on a VM where daemon is already
  running on port 862.
  Expected: check completes normally, returns
  measurement result. Does NOT hang.

---

### FIX-2 [LOW]: SLOGR_SCHEDULE_PATH env var in wrapper/entrypoint script

File: Dockerfile entrypoint or wrapper.sh

Problem:
  Agents always start in responder-only mode.
  No way to inject a pre-existing schedule at
  container startup without manual docker exec
  + file copy after the container is running.

Fix:
  In the entrypoint/wrapper script, before the
  exec java command:

    EXTRA_ARGS=""
    if [ -n "$SLOGR_SCHEDULE_PATH" ]; then
      EXTRA_ARGS="--config $SLOGR_SCHEDULE_PATH"
      echo "Loading schedule from: $SLOGR_SCHEDULE_PATH"
    fi

    exec java $JAVA_OPTS \
      -jar /app/agent.jar \
      daemon $EXTRA_ARGS

Usage after fix:
    docker run \
      --hostname=agent-us-east \
      -e SLOGR_API_KEY=sk_live_... \
      -e SLOGR_SCHEDULE_PATH=/config/schedule.json \
      -v /host/schedule.json:/config/schedule.json \
      ghcr.io/slogr-io/agent:1.0.2

Acceptance test:
  docker run with SLOGR_SCHEDULE_PATH set.
  Agent logs show schedule loaded on startup.
  Measurements begin without waiting for
  set_schedule push from Enterprise.

---

### FIX-3 [LOW]: Remove duplicate engine.start() in DaemonCommand

File: platform/src/main/kotlin/io/slogr/agent/platform/cli/DaemonCommand.kt

Problem:
  engine.start() is called twice — once at the
  correct location (line ~100) and again as dead
  code at line ~129 left over from a prior
  refactor. No runtime effect currently but
  misleading and could cause issues if
  engine.start() becomes non-idempotent.

Fix:
  Remove the duplicate call at line ~129.
  Keep only the first call.

Acceptance test:
  grep -c "engine.start" DaemonCommand.kt
  Expected: 1

---

## Build Order for v1.0.2

1. FIX-1 first — most impactful, requires
   touching MeasurementEngineImpl and two
   call sites
2. FIX-2 — wrapper script change, independent
3. FIX-3 — one line deletion, trivial

Run ./gradlew test after each fix.
All 571+ tests must pass before tagging.

## Tag and Release

After all three fixes:
  git tag v1.0.2
  ./gradlew buildFatJar
  docker build -t ghcr.io/slogr-io/agent:1.0.2
  docker build -t ghcr.io/slogr-io/agent:latest
  docker push ghcr.io/slogr-io/agent:1.0.2
  docker push ghcr.io/slogr-io/agent:latest
