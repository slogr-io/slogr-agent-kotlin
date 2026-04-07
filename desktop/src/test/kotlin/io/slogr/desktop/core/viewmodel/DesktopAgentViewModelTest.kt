package io.slogr.desktop.core.viewmodel

import io.slogr.agent.contracts.*
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DesktopAgentViewModelTest {

    private lateinit var viewModel: DesktopAgentViewModel
    private lateinit var profileManager: ProfileManager
    private lateinit var tempDir: java.nio.file.Path

    private val baseProfile = SlaProfile(
        name = "test", nPackets = 10, intervalMs = 50, waitTimeMs = 2000,
        dscp = 0, packetSize = 64,
        rttGreenMs = 100f, rttRedMs = 200f,
        jitterGreenMs = 30f, jitterRedMs = 50f,
        lossGreenPct = 1f, lossRedPct = 5f,
    )

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-vm-test")
        val store = DesktopSettingsStore(tempDir)
        val sm = DesktopStateManager(EncryptedKeyStore(tempDir))
        store.load(); sm.initialize()
        profileManager = ProfileManager(store, sm)
        profileManager.initialize(store.settings.value)
        viewModel = DesktopAgentViewModel()
    }

    @AfterTest
    fun tearDown() { tempDir.toFile().deleteRecursively() }

    private fun makeBundle(rtt: Float, loss: Float, jitter: Float, grade: SlaGrade) = MeasurementBundle(
        twamp = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "res", srcRegion = "local", dstCloud = "gcp", dstRegion = "us",
            windowTs = Clock.System.now(), profile = baseProfile,
            fwdMinRttMs = rtt, fwdAvgRttMs = rtt, fwdMaxRttMs = rtt,
            fwdJitterMs = jitter, fwdLossPct = loss,
            packetsSent = 10, packetsRecv = 10,
        ),
        grade = grade,
    )

    @Test
    fun `initial state is empty`() {
        assertTrue(viewModel.serverResults.value.isEmpty())
        assertTrue(viewModel.trafficGrades.value.isEmpty())
        assertNull(viewModel.overallGrade.value)
        assertFalse(viewModel.isMeasuring.value)
    }

    @Test
    fun `updateResult populates server result and traffic grades`() {
        viewModel.updateResult("s1", "Test", makeBundle(20f, 0f, 3f, SlaGrade.GREEN), profileManager)
        assertEquals(1, viewModel.serverResults.value.size)
        assertEquals(3, viewModel.trafficGrades.value.size) // 3 active profiles
        assertNotNull(viewModel.overallGrade.value)
        assertNotNull(viewModel.lastTestTime.value)
    }

    @Test
    fun `recordFailure marks server as unreachable`() {
        viewModel.recordFailure("s1", "Bad Server")
        val sr = viewModel.serverResults.value["s1"]!!
        assertFalse(sr.reachable)
        assertEquals(100f, sr.lossPct)
    }

    @Test
    fun `setMeasuring toggles state`() {
        viewModel.setMeasuring(true)
        assertTrue(viewModel.isMeasuring.value)
        viewModel.setMeasuring(false)
        assertFalse(viewModel.isMeasuring.value)
    }
}
