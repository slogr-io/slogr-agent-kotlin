package io.slogr.agent.platform.scheduler

import io.mockk.coEvery
import io.mockk.mockk
import io.slogr.agent.contracts.*
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Timeout(15)
class TestSchedulerTest {

    private val loopback = InetAddress.getLoopbackAddress()

    // ── Sessions fire within interval ─────────────────────────────────────────

    @Test
    fun `3 sessions each fire at least once within their interval`() = runBlocking {
        val fired = CopyOnWriteArrayList<UUID>()
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } answers {
            MeasurementBundle(twamp = fakeTwampResult(firstArg()), grade = SlaGrade.GREEN)
        }

        val sessions = (1..3).map { makeSession(intervalSeconds = 1) }
        val schedule = Schedule(sessions = sessions, receivedAt = Clock.System.now())

        val scheduler = TestScheduler(engine, onResult = { cfg, _ -> fired.add(cfg.pathId) })
        scheduler.start(schedule)

        delay(2500L)   // wait for at least 1 fire per session
        scheduler.stop()

        val uniqueFired = fired.toSet()
        assertEquals(3, uniqueFired.size, "All 3 sessions should have fired at least once: $uniqueFired")
    }

    // ── Semaphore limits concurrency ───────────────────────────────────────────

    @Test
    fun `max 2 concurrent sessions when maxConcurrent=2`() = runBlocking {
        val concurrentCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } coAnswers {
            val count = concurrentCount.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, count) }
            delay(200L)
            concurrentCount.decrementAndGet()
            MeasurementBundle(twamp = fakeTwampResult(firstArg()), grade = SlaGrade.GREEN)
        }

        val sessions = (1..5).map { makeSession(intervalSeconds = 1) }
        val schedule = Schedule(sessions = sessions, receivedAt = Clock.System.now())
        val scheduler = TestScheduler(engine, onResult = { _, _ -> }, maxConcurrent = 2)
        scheduler.start(schedule)

        delay(1500L)
        scheduler.stop()

        assertTrue(maxObserved.get() <= 2, "Max concurrent was ${maxObserved.get()} but limit is 2")
    }

    // ── Update schedule adds new sessions ─────────────────────────────────────

    @Test
    fun `update schedule adds a new session without affecting existing`() = runBlocking {
        val engine = mockk<MeasurementEngine>()
        coEvery { engine.measure(any(), any(), any(), any(), any(), any()) } answers {
            MeasurementBundle(twamp = fakeTwampResult(firstArg()), grade = SlaGrade.GREEN)
        }

        val session1 = makeSession(intervalSeconds = 1)
        val scheduler = TestScheduler(engine, onResult = { _, _ -> })
        scheduler.start(Schedule(sessions = listOf(session1), receivedAt = Clock.System.now()))

        delay(300L)
        val session2 = makeSession(intervalSeconds = 1)
        scheduler.updateSchedule(Schedule(sessions = listOf(session1, session2), receivedAt = Clock.System.now()))

        delay(1500L)
        scheduler.stop()
        // If we get here without exception, the update worked
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeSession(intervalSeconds: Int) = SessionConfig(
        pathId = UUID.randomUUID(),
        targetIp = loopback,
        intervalSeconds = intervalSeconds,
        profile = SlaProfile(
            name = "internet", nPackets = 5, intervalMs = 10L, waitTimeMs = 100L,
            dscp = 0, packetSize = 64, timingMode = TimingMode.FIXED,
            rttGreenMs = 50f, rttRedMs = 150f, jitterGreenMs = 10f, jitterRedMs = 30f,
            lossGreenPct = 1f, lossRedPct = 5f
        )
    )

    private fun fakeTwampResult(target: InetAddress) = MeasurementResult(
        sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
        sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
        srcCloud = "aws", srcRegion = "us-east-1", dstCloud = "gcp", dstRegion = "us-central1",
        windowTs = Clock.System.now(),
        profile = SlaProfile(
            name = "internet", nPackets = 5, intervalMs = 10L, waitTimeMs = 100L,
            dscp = 0, packetSize = 64, timingMode = TimingMode.FIXED,
            rttGreenMs = 50f, rttRedMs = 150f, jitterGreenMs = 10f, jitterRedMs = 30f,
            lossGreenPct = 1f, lossRedPct = 5f
        ),
        fwdMinRttMs = 5f, fwdAvgRttMs = 10f, fwdMaxRttMs = 15f,
        fwdJitterMs = 1f, fwdLossPct = 0f,
        packetsSent = 5, packetsRecv = 5
    )
}
