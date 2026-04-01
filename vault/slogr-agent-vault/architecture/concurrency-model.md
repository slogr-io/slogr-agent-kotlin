---
status: locked
version: 1.0
depends-on:
  - architecture/module-map
claude-code-context:
  - "Read when implementing threading, coroutines, or lifecycle management"
---

# Concurrency Model

## Design Principles

1. **Kotlin coroutines for I/O-bound work** (network calls, Pub/Sub polling, RabbitMQ publishing, OTLP export, ASN lookup)
2. **Dedicated threads for time-critical work** (TWAMP sender/reflector — following the thread-per-session model for accuracy depends on precise timing)
3. **JNI calls release the GIL** (JNI native methods are naturally concurrent — multiple TWAMP sessions can run simultaneously)
4. **Structured concurrency** — every coroutine has a parent scope; cancellation propagates cleanly on shutdown

## Thread Architecture on t3.micro

```
Main thread
├── CLI parser → decides mode → launches appropriate scope
│
├── [daemon mode] CoroutineScope("slogr-daemon")
│   ├── Scheduler coroutine (launches test sessions at intervals)
│   │   └── Per-session coroutines (N concurrent, bounded by semaphore)
│   │       ├── TWAMP sender thread (dedicated, via Dispatchers.IO)
│   │       ├── Traceroute coroutine (JNI call, Dispatchers.IO)
│   │       ├── ASN lookup (local MMDB, CPU-bound, Dispatchers.Default)
│   │       ├── Path change check (CPU-bound, Dispatchers.Default)
│   │       └── SLA evaluation (CPU-bound, Dispatchers.Default)
│   ├── RabbitMQ publisher coroutine (long-lived connection)
│   ├── Pub/Sub poller coroutine (polls every 5 seconds)
│   ├── OTLP exporter coroutine (batches and sends)
│   ├── Health reporter coroutine (periodic, every 60 seconds)
│   ├── Buffer drain coroutine (replays WAL entries)
│   └── TWAMP responder thread (NIO Selector, from Java — single thread)
│
├── [check mode] CoroutineScope("slogr-check")
│   └── Single measurement coroutine → print result → exit
```

## Concurrency Limits

| Resource | Limit | Rationale |
|----------|-------|-----------|
| Concurrent TWAMP sessions | 20 (configurable) | Each session = 1 thread + JNI socket. On t3.micro (2 vCPU), 20 is the practical ceiling before context-switch overhead dominates. |
| Concurrent traceroutes | 4 | Each traceroute sends multiple probe packets. More than 4 simultaneous traceroutes causes probe packet loss from self-congestion. |
| RabbitMQ publisher connections | 1 | Single persistent connection, multiple channels. |
| Pub/Sub poll interval | 5 seconds | Balance between command latency and API quota usage. |
| OTLP batch size | 100 metrics or 10 seconds | Whichever comes first. |
| WAL replay rate | 10 entries/second | Avoid flooding RabbitMQ on reconnect after long outage. |

The concurrent session limit is enforced by a `Semaphore(20)` in the scheduler. If all 20 permits are taken, new sessions wait until a running session completes.

## Shutdown Sequence

On SIGTERM or SIGINT:

```
1. Stop accepting new scheduled sessions (scheduler stops launching)
2. Cancel the Pub/Sub poller (stop accepting commands)
3. Wait up to 30 seconds for in-flight TWAMP sessions to complete
4. Cancel any sessions still running after 30s
5. Flush the OTLP exporter (send remaining batch)
6. Publish final health signal (publish_status = FAILING if forced)
7. Flush the WAL → attempt to drain to RabbitMQ (up to 10s)
8. Close RabbitMQ connection
9. Stop the TWAMP responder (NIO selector)
10. Exit
```

All timeouts are configurable. The systemd `TimeoutStopSec` should be set to 45 seconds to allow the full drain.

## Signal Handling

Register JVM shutdown hook:

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking {
        shutdownSequence()
    }
})
```

Also handle SIGTERM explicitly via `sun.misc.Signal` for cleaner logging.

## Error Isolation

- A failed TWAMP session does not affect other sessions. The exception is caught, logged, the session's failure counter increments, and the scheduler moves on.
- A RabbitMQ connection failure does not stop measurements. Results go to the WAL and OTLP (if configured). The RabbitMQ publisher reconnects with exponential backoff.
- A Pub/Sub failure does not stop measurements or reporting. The poller retries on the next interval.
- A JNI crash (segfault in native code) will kill the JVM. This is mitigated by thorough testing of the C code and by running the agent under systemd with `Restart=always`.
