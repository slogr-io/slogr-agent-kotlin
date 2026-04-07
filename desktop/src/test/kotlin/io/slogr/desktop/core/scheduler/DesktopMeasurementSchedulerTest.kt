package io.slogr.desktop.core.scheduler

import io.slogr.agent.contracts.*
import io.slogr.agent.contracts.interfaces.MeasurementEngine
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.settings.ServerEntry
import io.slogr.desktop.core.state.DesktopStateManager
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.net.InetAddress
import java.util.UUID
import kotlin.test.*

class DesktopMeasurementSchedulerTest {

    private val testServer = ServerEntry("srv-1", "127.0.0.1", 862, "Local")

    private class MockEngine : MeasurementEngine {
        var callCount = 0
        var shouldFail = false

        override suspend fun measure(
            target: InetAddress, targetPort: Int, profile: SlaProfile,
            traceroute: Boolean, authMode: TwampAuthMode, keyId: String?,
        ): MeasurementBundle {
            callCount++
            if (shouldFail) throw RuntimeException("fail")
            val r = MeasurementResult(
                sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
                sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
                srcCloud = "res", srcRegion = "local", dstCloud = "dev", dstRegion = "local",
                windowTs = Clock.System.now(), profile = profile,
                fwdMinRttMs = 5f, fwdAvgRttMs = 10f, fwdMaxRttMs = 15f,
                fwdJitterMs = 2f, fwdLossPct = 0f,
                packetsSent = profile.nPackets, packetsRecv = profile.nPackets,
            )
            return MeasurementBundle(twamp = r, grade = SlaGrade.GREEN)
        }

        override suspend fun twamp(target: InetAddress, targetPort: Int, profile: SlaProfile,
            authMode: TwampAuthMode, keyId: String?) = throw UnsupportedOperationException()
        override suspend fun traceroute(target: InetAddress, maxHops: Int, probesPerHop: Int,
            timeoutMs: Int, mode: TracerouteMode?) = throw UnsupportedOperationException()
        override fun shutdown() {}
    }

    private fun makePM(): ProfileManager {
        val tmp = java.nio.file.Files.createTempDirectory("slogr-sched")
        val ss = DesktopSettingsStore(tmp); ss.load()
        val sm = DesktopStateManager(EncryptedKeyStore(tmp)); sm.initialize()
        val pm = ProfileManager(ss, sm); pm.initialize(ss.settings.value)
        return pm
    }

    @Test
    fun `runOnce calls engine and updates viewModel`() = runBlocking {
        val eng = MockEngine()
        val vm = DesktopAgentViewModel()
        val pm = makePM()
        val sched = DesktopMeasurementScheduler(eng, vm, pm)
        sched.runOnce(listOf(testServer), tracerouteEnabled = false)
        assertEquals(1, eng.callCount)
        assertEquals(1, vm.serverResults.value.size)
        assertEquals(3, vm.trafficGrades.value.size)
    }

    @Test
    fun `runOnce with empty servers does nothing`() = runBlocking {
        val eng = MockEngine()
        val vm = DesktopAgentViewModel()
        val sched = DesktopMeasurementScheduler(eng, vm, makePM())
        sched.runOnce(emptyList(), tracerouteEnabled = false)
        assertEquals(0, eng.callCount)
    }

    @Test
    fun `engine failure records failure in viewModel`() = runBlocking {
        val eng = MockEngine().apply { shouldFail = true }
        val vm = DesktopAgentViewModel()
        val sched = DesktopMeasurementScheduler(eng, vm, makePM())
        sched.runOnce(listOf(testServer), tracerouteEnabled = false)
        val sr = vm.serverResults.value["srv-1"]!!
        assertFalse(sr.reachable)
    }
}
