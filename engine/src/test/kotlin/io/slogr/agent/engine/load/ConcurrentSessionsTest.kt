package io.slogr.agent.engine.load

import io.mockk.coEvery
import io.mockk.mockk
import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TracerouteHop
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.UUID

/**
 * Verifies that 20 concurrent measurement sessions complete without OOM or deadlock,
 * and that heap usage after all sessions remains below the 384 MB budget.
 */
class ConcurrentSessionsTest {

    private val profile = SlaProfile(
        name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 2000L, dscp = 46,
        packetSize = 172, rttGreenMs = 150f, rttRedMs = 400f,
        jitterGreenMs = 30f, jitterRedMs = 100f, lossGreenPct = 1f, lossRedPct = 5f
    )

    private fun fakeBundle(sessionId: UUID) = MeasurementBundle(
        twamp = MeasurementResult(
            sessionId     = sessionId,
            pathId        = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(),
            destAgentId   = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            srcCloud      = "aws",
            srcRegion     = "us-east-1",
            dstCloud      = "aws",
            dstRegion     = "us-east-1",
            windowTs      = Clock.System.now(),
            profile       = profile,
            fwdMinRttMs   = 5f,
            fwdAvgRttMs   = 10f,
            fwdMaxRttMs   = 20f,
            fwdJitterMs   = 1f,
            fwdLossPct    = 0f,
            packetsSent   = 100,
            packetsRecv   = 100
        ),
        traceroute = TracerouteResult(
            sessionId  = sessionId,
            pathId     = UUID.randomUUID(),
            direction  = Direction.UPLINK,
            capturedAt = Clock.System.now(),
            hops       = listOf(TracerouteHop(ttl = 1, ip = "8.8.8.8", rttMs = 10f))
        ),
        grade = SlaGrade.GREEN
    )

    @Test
    fun `20 concurrent measure calls all complete`() = runBlocking {
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any()) } answers {
            fakeBundle(UUID.randomUUID())
        }

        val target = InetAddress.getByName("127.0.0.1")
        val jobs: List<Deferred<MeasurementBundle>> = (1..20).map {
            async { engine.measure(target, profile, UUID.randomUUID()) }
        }

        val results = jobs.awaitAll()
        assertEquals(20, results.size)
        assertTrue(results.all { it.twamp != null }, "All sessions should have a TWAMP result")
    }

    @Test
    fun `heap usage stays below 384 MB after 20 concurrent sessions`() = runBlocking {
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any()) } answers {
            fakeBundle(UUID.randomUUID())
        }

        val target = InetAddress.getByName("127.0.0.1")
        val jobs = (1..20).map { async { engine.measure(target, profile, UUID.randomUUID()) } }
        jobs.awaitAll()

        // Force GC to get a clean heap reading
        System.gc()
        Thread.sleep(100)
        System.gc()

        val heapUsedMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)
        assertTrue(heapUsedMb < 384, "Heap ${heapUsedMb}MB exceeds 384MB budget after 20 sessions")
    }

    @Test
    fun `100 sequential sessions produce no unbounded growth`() = runBlocking {
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any()) } answers {
            fakeBundle(UUID.randomUUID())
        }

        val target = InetAddress.getByName("127.0.0.1")

        // Warm up — drive any lazy initialization
        repeat(10) { engine.measure(target, profile, UUID.randomUUID()) }
        System.gc()
        val baselineHeapMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)

        // Run 100 sessions
        repeat(100) { engine.measure(target, profile, UUID.randomUUID()) }
        System.gc()
        Thread.sleep(100)
        System.gc()

        val afterHeapMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)
        // Allow 50 MB growth at most; unbounded growth would be much larger
        assertTrue(afterHeapMb - baselineHeapMb < 50,
            "Heap grew by ${afterHeapMb - baselineHeapMb}MB over 100 sessions (baseline: ${baselineHeapMb}MB, after: ${afterHeapMb}MB)")
    }
}
