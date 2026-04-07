# Slogr Agent — v1.0.3 Backlog
Date: April 7 2026
Status: Scheduled
Branch: cut from master after v1.0.2

## Bugs found during GCP E2E testing (April 7 2026)

Root cause analysis performed on TwampController
and MeasurementEngineImpl after check command hung
on GCP VMs running v1.0.0. The v1.0.2 B2 fix
(startReflector=false) resolved the primary hang,
but three deeper bugs remain in the controller
error path.

---

### BUG-1 [HIGH]: onComplete never fires on connection failure

Files:
  engine/src/main/kotlin/io/slogr/agent/engine/twamp/controller/TwampController.kt
  engine/src/main/kotlin/io/slogr/agent/engine/twamp/controller/TwampControllerSession.kt

Problem:
  When a TCP connection to a remote reflector fails,
  the onComplete callback is never invoked. The
  suspendCancellableCoroutine in runTwamp() waits
  for a callback that will never come.

  Two failure paths, both broken:

  Path A — openConnection() throws (TwampController.kt:141-143):
    catch (e: Exception) {
        log.error("Failed to open connection...")
        // onComplete is NEVER called
    }
    The ConnectRequest is consumed from the queue
    but req.onComplete is never invoked. The
    coroutine waits forever.

  Path B — closeSession() on connect/read failure
  (TwampController.kt:179-183):
    fun closeSession(key: SelectionKey) {
        sessionMap.remove(key)?.close()
        key.channel().close()
        key.cancel()
    }
    TwampControllerSession.close() (line 340-345)
    sets state=CLOSED and closes the channel but
    does NOT call onComplete. The coroutine waits
    forever.

  Both paths are reached when:
  - Remote reflector is unreachable (firewall drop)
  - Connection refused (no TWAMP on target)
  - TCP handshake times out
  - Read error during TWAMP control exchange

  The withTimeoutOrNull(timeoutMs) in runTwamp()
  IS a safety net — it cancels the coroutine after
  ~6s for the internet profile. But the leaked
  session stays in sessionMap, and the NIO resources
  are not cleaned up.

Fix:
  In openConnection() catch block, fire onComplete
  with an error result:

    } catch (e: Exception) {
        log.error("Failed to open connection to ${req.reflectorIp}: ${e.message}")
        req.onComplete(SenderResult(
            emptyList(), req.config.count, 0,
            error = "connection failed: ${e.message}"
        ))
    }

  In TwampControllerSession.close(), fire onComplete
  if the session hasn't already completed:

    fun close() {
        if (state == State.CLOSED) return
        val wasComplete = state == State.COMPLETE
        state = State.CLOSED
        try { key.channel().close() } catch (_: Exception) {}
        key.cancel()
        if (!wasComplete) {
            onComplete(mergeSenderResults())
        }
    }

  This requires tracking whether onComplete has
  already been called. Add a private var completed
  = false flag, set it when onComplete fires in the
  normal path (line 287), check it in close().

Acceptance tests:
  1. check against unreachable IP (10.255.255.1):
     Expected: times out in <10s, exits 3.
     NOT expected: hangs indefinitely.
  2. check against IP with no TWAMP (e.g. 8.8.8.8):
     Expected: connection refused, falls back to
     ICMP/TCP, exits 0 or 3.
  3. Unit test: mock adapter that refuses connect,
     verify onComplete called with error result.

---

### BUG-2 [HIGH]: --port flag ignored (targetPort not passed through)

Files:
  engine/src/main/kotlin/io/slogr/agent/engine/MeasurementEngineImpl.kt
  engine/src/main/kotlin/io/slogr/agent/engine/twamp/controller/TwampController.kt

Problem:
  The targetPort parameter passed to measure() and
  runTwamp() is never forwarded to the controller.
  TwampController.connect() does not accept a port
  parameter — it always uses the port from its
  constructor (line 127):

    chan.connect(InetSocketAddress(req.reflectorIp, port))

  This means `slogr-agent check 10.0.0.1 --port 8862`
  silently ignores 8862 and connects to 862.

  In MeasurementEngineImpl.runTwamp() (line 169-175):
    controller.connect(
        reflectorIp  = target,
        config       = config,
        // targetPort is NOT passed
    )

Fix:
  Add port field to ConnectRequest:

    private data class ConnectRequest(
        val reflectorIp:  InetAddress,
        val reflectorPort: Int,    // ← new
        val config:       SenderConfig,
        ...
    )

  Update controller.connect() signature:

    fun connect(
        reflectorIp: InetAddress,
        reflectorPort: Int = port,  // ← new, defaults to constructor port
        ...
    )

  Update openConnection():

    chan.connect(InetSocketAddress(req.reflectorIp, req.reflectorPort))

  Update MeasurementEngineImpl.runTwamp():

    controller.connect(
        reflectorIp   = target,
        reflectorPort = targetPort,
        config        = config,
        ...
    )

Acceptance tests:
  1. Run TWAMP reflector on non-standard port 8862.
     Run check --port 8862. Verify it connects to
     8862, not 862.
  2. Run check without --port. Verify it connects
     to 862 (default).

---

### BUG-3 [LOW]: No invokeOnCancellation on suspendCancellableCoroutine

File:
  engine/src/main/kotlin/io/slogr/agent/engine/MeasurementEngineImpl.kt

Problem:
  runTwamp() uses suspendCancellableCoroutine
  without an invokeOnCancellation handler
  (line 168-176). When withTimeoutOrNull cancels
  the coroutine, the NIO connection attempt and
  session in the controller's sessionMap are leaked.
  Not a hang cause (timeout still fires) but a
  resource leak — the selector loop keeps the
  dead session alive until the controller is stopped.

Fix:
  Add invokeOnCancellation to clean up:

    suspendCancellableCoroutine { cont ->
        controller.connect(
            reflectorIp = target,
            ...
            onComplete  = { r -> cont.resume(r) }
        )
        cont.invokeOnCancellation {
            // Session cleanup happens via
            // withTimeoutOrNull cancellation.
            // Controller will purge closed sessions
            // in its selector loop.
        }
    }

  Alternatively, add a cancel(sessionId) method to
  TwampController that closes the channel and removes
  the session from sessionMap on timeout.

Acceptance test:
  Run check against unreachable IP. After timeout,
  verify controller sessionMap is empty (no leaked
  sessions). This requires a test-visible accessor
  or log assertion.

---

## Carried forward from v1.0.2

### FIX-4 [LOW]: SLOGR_SCHEDULE_PATH env var in wrapper script

(Unchanged from v1.0.2 backlog FIX-2)

File: deploy/wrapper.sh

Fix: Check SLOGR_SCHEDULE_PATH env var, pass
--config flag to daemon command.

### FIX-5 [LOW]: Remove duplicate engine.start() comment in DaemonCommand

(Unchanged from v1.0.2 backlog FIX-3)

File: platform/.../cli/DaemonCommand.kt line 129.
The comment references a duplicate that was already
removed. The comment itself is stale.

---

## Build Order for v1.0.3

1. BUG-1 first — highest impact, fixes silent
   connection failure. Requires changes to
   TwampController and TwampControllerSession.
2. BUG-2 — targetPort passthrough. Changes to
   TwampController.connect(), ConnectRequest,
   and MeasurementEngineImpl.runTwamp().
3. BUG-3 — invokeOnCancellation. Single file change.
4. FIX-4 — wrapper script. Independent.
5. FIX-5 — stale comment removal. Trivial.

Run ./gradlew test after each fix.
All 648+ tests must pass before tagging.
