package io.slogr.agent.engine.reflector

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PacketBufferPoolTest {

    @Test
    fun `borrow returns a buffer with the correct capacity`() {
        val pool = PacketBufferPool(capacity = 4, bufferSize = 256)
        val buf = pool.borrow()
        assertEquals(256, buf.capacity())
        assertTrue(buf.hasArray(), "Buffer must be heap-backed")
    }

    @Test
    fun `returnBuffer clears the buffer`() {
        val pool = PacketBufferPool(capacity = 4, bufferSize = 256)
        val buf = pool.borrow()
        buf.put(42.toByte())
        pool.returnBuffer(buf)
        // After return, buffer should be cleared (position = 0, limit = capacity)
        assertEquals(0, buf.position())
        assertEquals(256, buf.limit())
    }

    @Test
    fun `borrow after return reuses the same buffer instance`() {
        val pool = PacketBufferPool(capacity = 1, bufferSize = 128)
        val first = pool.borrow()
        pool.returnBuffer(first)
        val second = pool.borrow()
        assertSame(first, second, "Returned buffer should be reused")
    }

    @Test
    fun `borrow allocates fresh buffer when pool is exhausted`() {
        val pool = PacketBufferPool(capacity = 1, bufferSize = 64)
        val first = pool.borrow()   // drains the pool
        val second = pool.borrow()  // pool is empty — must allocate fresh
        assertNotNull(second)
        assertEquals(64, second.capacity())
        assertNotSame(first, second)
    }

    @Test
    fun `concurrent borrow and return from multiple threads`() {
        val pool = PacketBufferPool(capacity = 8, bufferSize = 128)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..16).map {
            Thread {
                try {
                    val buf = pool.borrow()
                    buf.put(1.toByte())
                    Thread.sleep(1)
                    pool.returnBuffer(buf)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(3_000) }
        assertEquals(0, errors.get(), "No errors expected under concurrent access")
    }

    @Test
    fun `default bufferSize is at least MAX_TWAMP_PACKET_SIZE`() {
        val pool = PacketBufferPool(capacity = 2)
        val buf = pool.borrow()
        assertTrue(buf.capacity() >= PacketBufferPool.MAX_TWAMP_PACKET_SIZE)
    }
}
