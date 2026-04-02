package io.slogr.agent.engine.reflector

import io.slogr.agent.engine.twamp.SessionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReflectorThreadPoolTest {

    private val pool = ReflectorThreadPool()

    @AfterEach
    fun tearDown() { pool.shutdown(2L) }

    @Test
    fun `poolSize equals 2 times available processors`() {
        assertEquals(Runtime.getRuntime().availableProcessors() * 2, pool.poolSize)
    }

    @Test
    fun `registerSession increments activeSessionCount`() {
        assertEquals(0, pool.activeSessionCount)
        pool.registerSession(makeSession())
        assertEquals(1, pool.activeSessionCount)
    }

    @Test
    fun `unregisterSession decrements activeSessionCount`() {
        val session = makeSession()
        pool.registerSession(session)
        pool.unregisterSession(session.sessionId)
        assertEquals(0, pool.activeSessionCount)
    }

    @Test
    fun `unregisterSession on unknown id is safe`() {
        val unknownId = SessionId(ipv4 = 0, timestamp = 0L, randNumber = 99999)
        assertDoesNotThrow { pool.unregisterSession(unknownId) }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `submit executes runnable on a pool thread`() {
        val latch = CountDownLatch(1)
        pool.submit { latch.countDown() }
        assertTrue(latch.await(4, TimeUnit.SECONDS), "Submitted task did not execute")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `activeThreadCount reflects running tasks`() {
        val latch  = CountDownLatch(1)
        val started = CountDownLatch(1)
        pool.submit {
            started.countDown()
            latch.await()
        }
        assertTrue(started.await(4, TimeUnit.SECONDS))
        assertTrue(pool.activeThreadCount >= 1)
        latch.countDown()
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `concurrent submissions all execute without loss`() {
        val count = AtomicInteger(0)
        val latch  = CountDownLatch(20)
        repeat(20) {
            pool.submit {
                count.incrementAndGet()
                latch.countDown()
            }
        }
        assertTrue(latch.await(4, TimeUnit.SECONDS), "Not all tasks completed")
        assertEquals(20, count.get())
    }

    @Test
    fun `bufferPool capacity equals poolSize times 4`() {
        assertEquals(pool.poolSize * 4, pool.bufferPool.capacity)
    }

    @Test
    fun `scheduler can schedule tasks`() {
        val latch = CountDownLatch(1)
        pool.scheduler.schedule({ latch.countDown() }, 50, TimeUnit.MILLISECONDS)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private var sidCounter = 0

    private fun makeSession() = ReflectorSession(
        sessionId     = SessionId(ipv4 = 0x7F000001, timestamp = System.nanoTime(), randNumber = ++sidCounter),
        senderAddress = InetSocketAddress("127.0.0.1", 20000)
    )
}
