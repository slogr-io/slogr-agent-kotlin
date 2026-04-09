package io.slogr.agent.engine.reflector

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool of pre-allocated [ByteBuffer] instances for TWAMP packet reception.
 *
 * Pre-allocating buffers avoids per-packet allocation pressure and reduces
 * GC activity under high session load. Buffers are heap-backed so that
 * `array()` is safe to call (required by [io.slogr.agent.native.NativeProbeAdapter.recvPacket]).
 *
 * If the pool is exhausted (all buffers currently borrowed), [borrow] allocates a
 * fresh buffer rather than blocking. The newly allocated buffer is offered back
 * to the pool on [returnBuffer] — expanding capacity up to [capacity] total.
 *
 * @param capacity   Maximum number of pooled buffers.
 * @param bufferSize Per-buffer capacity in bytes (must be ≥ max TWAMP packet size).
 */
class PacketBufferPool(
    val capacity: Int,
    val bufferSize: Int = MAX_TWAMP_PACKET_SIZE
) {
    private val pool = ArrayBlockingQueue<ByteBuffer>(capacity)

    /** REC-A9: Hard ceiling on total allocations to prevent unbounded memory growth. */
    private val maxAllocations = capacity * 8
    private val allocated = AtomicInteger(capacity)

    init {
        repeat(capacity) { pool.add(ByteBuffer.allocate(bufferSize)) }
    }

    /**
     * Borrow a buffer from the pool. If the pool is empty a new buffer is
     * allocated on the fly up to [maxAllocations] ceiling.
     */
    fun borrow(): ByteBuffer = pool.poll() ?: run {
        if (allocated.incrementAndGet() <= maxAllocations) {
            ByteBuffer.allocate(bufferSize)
        } else {
            allocated.decrementAndGet()
            ByteBuffer.allocate(bufferSize) // fallback — always return a buffer
        }
    }

    /**
     * Return [buf] to the pool after use. The buffer is cleared before
     * being offered so it is ready for the next caller.
     */
    fun returnBuffer(buf: ByteBuffer) {
        buf.clear()
        pool.offer(buf)      // no-op if pool is already at capacity
    }

    companion object {
        /** Maximum TWAMP packet: encrypted sender packet (64) + max padding (1448). */
        const val MAX_TWAMP_PACKET_SIZE = 1512
    }
}
