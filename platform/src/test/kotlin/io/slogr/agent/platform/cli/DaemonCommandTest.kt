package io.slogr.agent.platform.cli

import com.github.ajalt.clikt.core.parse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.slogr.agent.contracts.Schedule
import io.slogr.agent.contracts.SessionConfig
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.contracts.interfaces.CredentialStore
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.agent.engine.probe.IcmpPingProbe
import io.slogr.agent.engine.probe.TcpConnectProbe
import io.slogr.agent.engine.traceroute.TracerouteOrchestrator
import io.slogr.agent.platform.config.AgentConfig
import io.slogr.agent.platform.otlp.OtlpExporter
import io.slogr.agent.platform.scheduler.ScheduleStore
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R2-BUG-01: engine.start() must be called on daemon startup so the TWAMP
 *             reflector binds 0.0.0.0:862 before any remote controller connects.
 *
 * R2-BUG-02: --config <file> must load the schedule from that file, not from
 *             the persisted ScheduleStore, so `daemon --config schedule.json`
 *             actually uses the provided file.
 */
@Timeout(10)
class DaemonCommandTest {

    @TempDir
    lateinit var tempDir: File

    // ── R2-BUG-01: engine.start() is called immediately on daemon startup ─────

    @Test
    fun `R2-BUG-01 engine start is called before scheduler begins`() {
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val scheduleStore = mockk<ScheduleStore>(relaxed = true)
        every { scheduleStore.load() } returns null

        val ctx = makeCtx(engine = engine, scheduleStore = scheduleStore)

        val latch = CountDownLatch(1)
        val t = Thread {
            try {
                DaemonCommand(ctx).parse(emptyList<String>())
            } catch (_: InterruptedException) {
                // expected
            } finally {
                latch.countDown()
            }
        }.also { it.isDaemon = true; it.start() }

        // Give the command time to run past engine.start() and into join()
        Thread.sleep(300)
        verify { engine.start() }

        t.interrupt()
        latch.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun `R2-BUG-01 engine start is called even with no schedule configured`() {
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val scheduleStore = mockk<ScheduleStore>(relaxed = true)
        every { scheduleStore.load() } returns null   // responder-only mode

        val ctx = makeCtx(engine = engine, scheduleStore = scheduleStore)

        val t = Thread {
            try { DaemonCommand(ctx).parse(emptyList<String>()) }
            catch (_: InterruptedException) { /* expected */ }
        }.also { it.isDaemon = true; it.start() }

        Thread.sleep(300)
        // Must call start() regardless of schedule state
        verify { engine.start() }
        t.interrupt()
        t.join(2000)
    }

    // ── R2-BUG-02: --config file → loaded from file, scheduleStore not touched ─

    @Test
    fun `R2-BUG-02 --config existing file loads schedule from file not scheduleStore`() {
        val scheduleStore = mockk<ScheduleStore>(relaxed = true)
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val ctx = makeCtx(engine = engine, scheduleStore = scheduleStore)

        val configFile = File(tempDir, "test-schedule.json")
        configFile.writeText(Json.encodeToString(Schedule.serializer(), makeSchedule(sessions = 2)))

        val t = Thread {
            try { DaemonCommand(ctx).parse(listOf("--config", configFile.absolutePath)) }
            catch (_: InterruptedException) { /* expected */ }
        }.also { it.isDaemon = true; it.start() }

        Thread.sleep(300)
        // scheduleStore.load() must NOT be called when --config file exists
        verify(exactly = 0) { scheduleStore.load() }
        t.interrupt()
        t.join(2000)
    }

    @Test
    fun `R2-BUG-02 --config missing file falls back to scheduleStore`() {
        val scheduleStore = mockk<ScheduleStore>(relaxed = true)
        every { scheduleStore.load() } returns null
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val ctx = makeCtx(engine = engine, scheduleStore = scheduleStore)

        val missingPath = File(tempDir, "does-not-exist.json").absolutePath

        val t = Thread {
            try { DaemonCommand(ctx).parse(listOf("--config", missingPath)) }
            catch (_: InterruptedException) { /* expected */ }
        }.also { it.isDaemon = true; it.start() }

        Thread.sleep(300)
        // File missing → must fall back to scheduleStore.load()
        verify { scheduleStore.load() }
        t.interrupt()
        t.join(2000)
    }

    @Test
    fun `R2-BUG-02 no --config flag uses scheduleStore`() {
        val scheduleStore = mockk<ScheduleStore>(relaxed = true)
        every { scheduleStore.load() } returns null
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val ctx = makeCtx(engine = engine, scheduleStore = scheduleStore)

        val t = Thread {
            try { DaemonCommand(ctx).parse(emptyList<String>()) }
            catch (_: InterruptedException) { /* expected */ }
        }.also { it.isDaemon = true; it.start() }

        Thread.sleep(300)
        verify { scheduleStore.load() }
        t.interrupt()
        t.join(2000)
    }

    // ── R2-BUG-02: corrupt config file falls back to scheduleStore ────────────

    @Test
    fun `R2-BUG-02 corrupt config file falls back to scheduleStore without crash`() {
        val scheduleStore = mockk<ScheduleStore>(relaxed = true)
        every { scheduleStore.load() } returns null
        val engine = mockk<MeasurementEngine>(relaxed = true)
        val ctx = makeCtx(engine = engine, scheduleStore = scheduleStore)

        val badFile = File(tempDir, "bad.json")
        badFile.writeText("this is {{{ not valid json")

        // Should not throw — gracefully falls back
        var thrown: Throwable? = null
        val latch = CountDownLatch(1)
        val t = Thread {
            try { DaemonCommand(ctx).parse(listOf("--config", badFile.absolutePath)) }
            catch (e: InterruptedException) { /* expected shutdown */ }
            catch (e: Throwable) { thrown = e }
            finally { latch.countDown() }
        }.also { it.isDaemon = true; it.start() }

        Thread.sleep(300)
        t.interrupt()
        latch.await(2, TimeUnit.SECONDS)

        // Either it fell back (thrown==null, join completed) or interrupted before crash
        // Key: no unhandled exception other than InterruptedException propagates
        // If thrown is non-null and the thread is still alive the command crashed — fail
        assertNull(thrown, "DaemonCommand must not propagate exceptions from corrupt config: $thrown")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCtx(
        engine: MeasurementEngine = mockk(relaxed = true),
        scheduleStore: ScheduleStore = mockk(relaxed = true)
    ): CliContext {
        val creds = mockk<CredentialStore>(relaxed = true)
        every { creds.isConnected() } returns false
        every { creds.load() } returns null
        return CliContext(
            config                 = AgentConfig(),
            engine                 = engine,
            credentialStore        = creds,
            scheduleStore          = scheduleStore,
            otlpExporter           = mockk<OtlpExporter>(relaxed = true),
            icmpPingProbe          = mockk<IcmpPingProbe>(relaxed = true),
            tcpConnectProbe        = mockk<TcpConnectProbe>(relaxed = true),
            tracerouteOrchestrator = mockk<TracerouteOrchestrator>(relaxed = true)
        )
    }

    private fun makeSchedule(sessions: Int = 1): Schedule {
        val profile = SlaProfile(
            name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 500L,
            dscp = 46, packetSize = 64, timingMode = TimingMode.FIXED,
            rttGreenMs = 30f, rttRedMs = 80f, jitterGreenMs = 5f, jitterRedMs = 15f,
            lossGreenPct = 0.5f, lossRedPct = 2f
        )
        return Schedule(
            sessions = (1..sessions).map {
                SessionConfig(
                    pathId    = UUID.randomUUID(),
                    targetIp  = InetAddress.getLoopbackAddress(),
                    profile   = profile
                )
            },
            receivedAt = Clock.System.now()
        )
    }
}
