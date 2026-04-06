package io.slogr.desktop.core.scheduler

import io.slogr.agent.contracts.*
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.desktop.core.reflectors.Reflector
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.net.InetAddress
import java.util.UUID
import kotlin.test.*

class DesktopMeasurementSchedulerTest {

    private val internetProfile = SlaProfile(
        name = "internet", nPackets = 10, intervalMs = 50, waitTimeMs = 2000,
        dscp = 0, packetSize = 64,
        rttGreenMs = 100f, rttRedMs = 200f,
        jitterGreenMs = 30f, jitterRedMs = 50f,
        lossGreenPct = 1f, lossRedPct = 5f,
    )

    private val testReflector = Reflector(
        id = "test-1", region = "local", cloud = "dev",
        host = "127.0.0.1", port = 862,
        latitude = 0.0, longitude = 0.0, tier = "free",
    )

    /** A mock engine that returns a canned result without network I/O. */
    private class MockEngine : MeasurementEngine {
        var measureCallCount = 0
        var shouldFail = false

        override suspend fun measure(
            target: InetAddress,
            targetPort: Int,
            profile: SlaProfile,
            traceroute: Boolean,
            authMode: TwampAuthMode,
            keyId: String?,
        ): MeasurementBundle {
            measureCallCount++
            if (shouldFail) throw RuntimeException("Simulated failure")
            val result = MeasurementResult(
                sessionId = UUID.randomUUID(),
                pathId = UUID.randomUUID(),
                sourceAgentId = UUID.randomUUID(),
                destAgentId = UUID.randomUUID(),
                srcCloud = "residential", srcRegion = "local",
                dstCloud = "dev", dstRegion = "local",
                windowTs = Clock.System.now(),
                profile = profile,
                fwdMinRttMs = 5f, fwdAvgRttMs = 10f, fwdMaxRttMs = 15f,
                fwdJitterMs = 2f, fwdLossPct = 0f,
                packetsSent = profile.nPackets, packetsRecv = profile.nPackets,
            )
            return MeasurementBundle(twamp = result, grade = SlaGrade.GREEN)
        }

        override suspend fun twamp(
            target: InetAddress, targetPort: Int, profile: SlaProfile,
            authMode: TwampAuthMode, keyId: String?,
        ): MeasurementResult = throw UnsupportedOperationException()

        override suspend fun traceroute(
            target: InetAddress, maxHops: Int, probesPerHop: Int,
            timeoutMs: Int, mode: TracerouteMode?,
        ): TracerouteResult = throw UnsupportedOperationException()

        override fun shutdown() {}
    }

    @Test
    fun `runOnce calls engine and updates viewModel`() = runBlocking {
        val engine = MockEngine()
        val viewModel = DesktopAgentViewModel()
        val scheduler = DesktopMeasurementScheduler(engine, viewModel)

        scheduler.runOnce(listOf(testReflector), internetProfile, tracerouteEnabled = false)

        assertEquals(1, engine.measureCallCount)
        assertEquals(1, viewModel.results.value.size)
        assertEquals(SlaGrade.GREEN, viewModel.overallGrade.value)
        assertFalse(viewModel.isMeasuring.value) // should be false after cycle
    }

    @Test
    fun `runOnce with multiple reflectors measures all`() = runBlocking {
        val engine = MockEngine()
        val viewModel = DesktopAgentViewModel()
        val scheduler = DesktopMeasurementScheduler(engine, viewModel)

        val reflectors = listOf(
            testReflector,
            testReflector.copy(id = "test-2", region = "us-east"),
            testReflector.copy(id = "test-3", region = "eu-west"),
        )

        scheduler.runOnce(reflectors, internetProfile, tracerouteEnabled = false)

        assertEquals(3, engine.measureCallCount)
        assertEquals(3, viewModel.results.value.size)
    }

    @Test
    fun `runOnce with engine failure records failure in viewModel`() = runBlocking {
        val engine = MockEngine().apply { shouldFail = true }
        val viewModel = DesktopAgentViewModel()
        val scheduler = DesktopMeasurementScheduler(engine, viewModel)

        scheduler.runOnce(listOf(testReflector), internetProfile, tracerouteEnabled = false)

        assertEquals(1, viewModel.results.value.size)
        assertEquals(SlaGrade.RED, viewModel.overallGrade.value)
    }

    @Test
    fun `runOnce with empty reflectors does nothing`() = runBlocking {
        val engine = MockEngine()
        val viewModel = DesktopAgentViewModel()
        val scheduler = DesktopMeasurementScheduler(engine, viewModel)

        scheduler.runOnce(emptyList(), internetProfile, tracerouteEnabled = false)

        assertEquals(0, engine.measureCallCount)
        assertTrue(viewModel.results.value.isEmpty())
    }

    @Test
    fun `isMeasuring is true during cycle and false after`() = runBlocking {
        val engine = MockEngine()
        val viewModel = DesktopAgentViewModel()
        val scheduler = DesktopMeasurementScheduler(engine, viewModel)

        assertFalse(viewModel.isMeasuring.value)
        scheduler.runOnce(listOf(testReflector), internetProfile, tracerouteEnabled = false)
        assertFalse(viewModel.isMeasuring.value)
    }
}
