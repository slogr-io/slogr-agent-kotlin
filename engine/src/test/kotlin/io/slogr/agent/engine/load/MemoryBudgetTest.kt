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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.UUID

/**
 * Verifies the JVM heap stays within the 384 MB budget after a sustained measurement workload.
 *
 * The engine module has no accumulating state of its own — heap growth after many sessions
 * would indicate a memory leak (e.g., unbounded collections in controllers or session maps
 * that don't clean up completed sessions).
 */
class MemoryBudgetTest {

    private val profile = SlaProfile(
        name = "default", nPackets = 100, intervalMs = 20L, waitTimeMs = 2000L, dscp = 0,
        packetSize = 172, rttGreenMs = 150f, rttRedMs = 400f,
        jitterGreenMs = 30f, jitterRedMs = 100f, lossGreenPct = 1f, lossRedPct = 5f
    )

    private fun fakeBundle() = MeasurementBundle(
        twamp = MeasurementResult(
            sessionId     = UUID.randomUUID(),
            pathId        = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(),
            destAgentId   = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            srcCloud      = "aws", srcRegion = "us-east-1",
            dstCloud      = "aws", dstRegion = "us-east-1",
            windowTs      = Clock.System.now(),
            profile       = profile,
            fwdMinRttMs = 5f, fwdAvgRttMs = 10f, fwdMaxRttMs = 20f,
            fwdJitterMs = 1f, fwdLossPct = 0f,
            packetsSent = 100, packetsRecv = 100
        ),
        traceroute = TracerouteResult(
            sessionId  = UUID.randomUUID(),
            pathId     = UUID.randomUUID(),
            direction  = Direction.UPLINK,
            capturedAt = Clock.System.now(),
            hops       = listOf(TracerouteHop(ttl = 1, ip = "8.8.8.8", rttMs = 10f))
        ),
        grade = SlaGrade.GREEN
    )

    @Test
    fun `heap stays below 384 MB after 300 mock sessions`() = runBlocking {
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } answers { fakeBundle() }

        val target = InetAddress.getByName("127.0.0.1")

        // Warm-up pass — drive lazy initialization before taking baseline
        repeat(10) { engine.measure(target = target, profile = profile) }
        System.gc()
        val baselineMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)

        // 300 sessions — no results held; GC should reclaim each bundle
        repeat(300) { engine.measure(target = target, profile = profile) }

        System.gc()
        Thread.sleep(100)
        System.gc()

        val usedMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)
        assertTrue(usedMb < 384,
            "Heap ${usedMb}MB exceeds 384MB budget after 300 sessions")
        assertTrue(usedMb - baselineMb < 50,
            "Heap grew by ${usedMb - baselineMb}MB over 300 sessions — possible leak (baseline: ${baselineMb}MB)")
    }
}
