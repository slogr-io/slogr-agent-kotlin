package io.slogr.agent.engine.twamp.integration

import io.slogr.agent.engine.twamp.TwampMode
import io.slogr.agent.engine.twamp.auth.ModePreferenceChain
import io.slogr.agent.engine.twamp.controller.SenderConfig
import io.slogr.agent.engine.twamp.controller.SenderResult
import io.slogr.agent.engine.twamp.controller.TwampController
import io.slogr.agent.engine.twamp.responder.TwampReflector
import io.slogr.agent.native.JavaUdpTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * In-JVM loopback test: TwampController ↔ TwampReflector on localhost.
 *
 * Uses [JavaUdpTransport] (pure-Java DatagramSocket) so this test runs on
 * any OS — no JNI library required.
 */
class TwampLoopbackTest {

    private val loopback = InetAddress.getLoopbackAddress()

    /** Build a minimal SenderConfig for quick loopback sessions. */
    private fun quickConfig(count: Int = 20, intervalMs: Long = 10L, waitMs: Long = 500L) =
        SenderConfig(
            count         = count,
            intervalMs    = intervalMs,
            waitTimeMs    = waitMs,
            paddingLength = 0,
            dscp          = 0
        )

    /**
     * Start a reflector on an ephemeral port and return it once bound.
     * Polls [TwampReflector.actualPort] until > 0 (max 3 s).
     */
    private fun startReflector(adapter: JavaUdpTransport): TwampReflector {
        val reflector = TwampReflector(adapter = adapter, listenPort = 0, bindIp = loopback)
        reflector.start()
        val deadline = System.currentTimeMillis() + 3_000L
        while (reflector.actualPort == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(reflector.actualPort > 0, "Reflector did not bind within 3 s")
        return reflector
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `unauthenticated loopback - 20 packets - zero loss`() {
        val adapter  = JavaUdpTransport()
        val reflector = startReflector(adapter)
        val controller = TwampController(
            adapter  = adapter,
            port     = reflector.actualPort,
            localIp  = loopback
        )
        controller.start()

        val latch  = CountDownLatch(1)
        val result = AtomicReference<SenderResult>()

        controller.connect(
            reflectorIp = loopback,
            config      = quickConfig(count = 20),
            modeChain   = ModePreferenceChain().prefer(TwampMode.UNAUTHENTICATED),
            onComplete  = { r -> result.set(r); latch.countDown() }
        )

        assertTrue(latch.await(25, TimeUnit.SECONDS), "Session did not complete within timeout")
        controller.stop()
        reflector.stop()

        val r = result.get()
        assertNotNull(r, "SenderResult must not be null")
        assertNull(r.error, "No error expected: ${r.error}")
        assertEquals(20, r.packetsSent)
        assertEquals(20, r.packetsRecv, "All 20 loopback packets must be received")
        assertEquals(0.0f, lossPercent(r.packetsSent, r.packetsRecv))
        assertTrue(r.packets.isNotEmpty())
        // Sanity: all fwd+rev delays should be positive (non-negative at minimum)
        r.packets.forEach { pkt ->
            assertTrue(pkt.fwdDelayMs >= 0f, "fwdDelayMs must be non-negative, was ${pkt.fwdDelayMs}")
            assertTrue(pkt.revDelayMs >= 0f, "revDelayMs must be non-negative, was ${pkt.revDelayMs}")
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `unauthenticated loopback - 100 packets - packetsRecv == 100 and fwdLossPct == 0`() {
        val adapter   = JavaUdpTransport()
        val reflector  = startReflector(adapter)
        val controller = TwampController(
            adapter  = adapter,
            port     = reflector.actualPort,
            localIp  = loopback
        )
        controller.start()

        val latch  = CountDownLatch(1)
        val result = AtomicReference<SenderResult>()

        controller.connect(
            reflectorIp = loopback,
            config      = quickConfig(count = 100, intervalMs = 5L, waitMs = 1000L),
            modeChain   = ModePreferenceChain().prefer(TwampMode.UNAUTHENTICATED),
            onComplete  = { r -> result.set(r); latch.countDown() }
        )

        assertTrue(latch.await(25, TimeUnit.SECONDS), "100-packet session timed out")
        controller.stop()
        reflector.stop()

        val r = result.get()
        assertNotNull(r)
        assertNull(r.error, "No error expected: ${r.error}")
        assertEquals(100, r.packetsSent)
        assertEquals(100, r.packetsRecv, "All 100 loopback packets must be received")
        assertEquals(0.0f, lossPercent(r.packetsSent, r.packetsRecv))
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `sequential sessions on same controller - both complete without error`() {
        val adapter   = JavaUdpTransport()
        val reflector  = startReflector(adapter)
        val controller = TwampController(
            adapter  = adapter,
            port     = reflector.actualPort,
            localIp  = loopback
        )
        controller.start()

        repeat(2) { sessionIndex ->
            val latch  = CountDownLatch(1)
            val result = AtomicReference<SenderResult>()
            controller.connect(
                reflectorIp = loopback,
                config      = quickConfig(count = 10),
                modeChain   = ModePreferenceChain().prefer(TwampMode.UNAUTHENTICATED),
                onComplete  = { r -> result.set(r); latch.countDown() }
            )
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Session $sessionIndex timed out")
            assertNull(result.get().error, "Session $sessionIndex should have no error")
            assertEquals(10, result.get().packetsRecv,
                "Session $sessionIndex: all packets must be received")
        }

        controller.stop()
        reflector.stop()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun lossPercent(sent: Int, recv: Int): Float {
        if (sent <= 0) return 0f
        return (sent - recv).coerceAtLeast(0).toFloat() / sent * 100f
    }
}
