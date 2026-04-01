# Thread Pool Reflector

**Status:** Locked
**Replaces:** R1 thread-per-session model in `TwampSessionReflector`

---

## Problem

R1: Each inbound TWAMP session gets a dedicated thread. At 10,000 concurrent sessions, that's 10,000 threads × ~1MB stack = 10GB. JVM context-switching overhead degrades performance.

## Solution

Thread pool + per-session UDP sockets + kernel-level `SO_TIMESTAMPING`.

```
Inbound packet
    │
    ▼
Kernel captures T2 via SO_TIMESTAMPING in recvmsg() ← timestamp is INDEPENDENT of thread pool state
    │
    ▼
JNI recvPacket() returns (data + T2) to available thread pool worker
    │
    ▼
Worker looks up session in ConcurrentHashMap<SessionId, ReflectorSession>
    │
    ▼
Worker builds reflected packet, captures T3 via System.nanoTime()
    │
    ▼
Worker calls JNI sendPacket()
    │
    ▼
Worker returns to pool for next packet
```

## Key Design Rules

### Thread Pool Sizing
```kotlin
val poolSize = Runtime.getRuntime().availableProcessors() * 2
```

Auto-scales with VM size:
- t3.micro (2 cores): 4 workers → handles ~100 sessions
- c5.4xlarge (16 cores): 32 workers → handles ~10,000 sessions
- Same binary, no config change.

### T2 Timestamp Accuracy (Critical)

T2 MUST be captured at kernel level via `SO_TIMESTAMPING` in the JNI `recvmsg()` call. NOT at Java `System.nanoTime()` when the thread pool worker picks up the packet. If all threads are busy, a packet waits in the kernel buffer. If T2 is captured when the worker reads it (late), the `(T3-T2)` delta looks small but the actual processing wait is invisible. The sender underestimates reflector delay, inflating measured RTT.

The JNI layer (`twampUdp.c`) already uses `recvmsg()` — the R2 change is to return the kernel timestamp alongside the packet data.

### Per-Session UDP Sockets (Critical)

DO NOT multiplex all sessions onto a single `DatagramChannel`. Java NIO `DatagramChannel.send()` has a `synchronized` block that causes lock contention under concurrent access. Each session keeps its own `DatagramSocket`. The thread pool manages which worker handles which socket, but the socket-per-session model is preserved.

### Session State

```kotlin
private val sessions = ConcurrentHashMap<SessionId, ReflectorSession>()

data class ReflectorSession(
    val sessionId: SessionId,
    val socket: DatagramSocket,
    val senderAddress: InetSocketAddress,
    val createdAt: Instant,
    val packetCount: AtomicLong = AtomicLong(0)
)
```

Session is created when the TWAMP control plane accepts a new session. Session is removed when the control plane closes the session or after REFWAIT timeout (default 900 seconds, configurable).

### Packet Buffer Pool

Pre-allocate `ByteBuffer` instances to avoid GC pressure under load:

```kotlin
private val bufferPool = ArrayBlockingQueue<ByteBuffer>(poolSize * 4)

init {
    repeat(poolSize * 4) {
        bufferPool.add(ByteBuffer.allocateDirect(MAX_TWAMP_PACKET_SIZE))
    }
}

fun borrowBuffer(): ByteBuffer = bufferPool.poll() ?: ByteBuffer.allocateDirect(MAX_TWAMP_PACKET_SIZE)
fun returnBuffer(buf: ByteBuffer) { buf.clear(); bufferPool.offer(buf) }
```

### Health Signal

Report `active_responder_sessions` in the health signal so the SaaS knows when a mesh agent is getting hot and can rebalance the schedule:

```json
{
  "active_responder_sessions": 847,
  "pool_size": 32,
  "pool_active_threads": 28,
  "pool_queue_depth": 3
}
```

## What NOT to Do

| Approach | Why it fails |
|----------|-------------|
| NIO DatagramChannel with Selector for UDP | `send()` has `synchronized` — lock contention under load |
| T2 captured at `Selector.select()` return | Selector batches events — T2 wrong for all but first packet |
| Shared receive buffer across sessions | Buffer contention adds variable latency |
| GC during reflection | `System.nanoTime()` includes GC time. Pre-allocate buffers. |
| Single socket for all sessions | Can't distinguish packets from different senders without parsing |

## Files

| File | Action |
|------|--------|
| `engine/reflector/ReflectorThreadPool.kt` | NEW — thread pool manager |
| `engine/reflector/ReflectorSession.kt` | NEW — per-session state |
| `engine/reflector/PacketBufferPool.kt` | NEW — pre-allocated ByteBuffer pool |
| `engine/reflector/TwampSessionReflector.kt` | MODIFY — swap internals from thread-per-session to pool dispatch |
| `native/src/twampUdp.c` | MODIFY — return `SO_TIMESTAMPING` kernel timestamp alongside packet data |
| Tests | Load test with 1000 concurrent sessions on 4-core VM. Verify T2 accuracy. Verify no RTT measurement degradation. |
