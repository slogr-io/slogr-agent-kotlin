package io.slogr.desktop.core.viewmodel

import io.slogr.agent.contracts.*
import kotlinx.datetime.Clock
import java.util.UUID
import kotlin.test.*

class DesktopAgentViewModelTest {

    private lateinit var viewModel: DesktopAgentViewModel

    private val internetProfile = SlaProfile(
        name = "internet", nPackets = 10, intervalMs = 50, waitTimeMs = 2000,
        dscp = 0, packetSize = 64,
        rttGreenMs = 100f, rttRedMs = 200f,
        jitterGreenMs = 30f, jitterRedMs = 50f,
        lossGreenPct = 1f, lossRedPct = 5f,
    )

    @BeforeTest
    fun setUp() {
        viewModel = DesktopAgentViewModel()
    }

    private fun makeBundle(rtt: Float, loss: Float, jitter: Float, grade: SlaGrade): MeasurementBundle {
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(),
            pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(),
            destAgentId = UUID.randomUUID(),
            srcCloud = "residential", srcRegion = "local",
            dstCloud = "aws", dstRegion = "us-east",
            windowTs = Clock.System.now(),
            profile = internetProfile,
            fwdMinRttMs = rtt, fwdAvgRttMs = rtt, fwdMaxRttMs = rtt,
            fwdJitterMs = jitter, fwdLossPct = loss,
            packetsSent = 10, packetsRecv = 10,
        )
        return MeasurementBundle(twamp = result, grade = grade)
    }

    @Test
    fun `initial state has no results`() {
        assertTrue(viewModel.results.value.isEmpty())
        assertNull(viewModel.overallGrade.value)
        assertNull(viewModel.lastTestTime.value)
        assertFalse(viewModel.isMeasuring.value)
    }

    @Test
    fun `updateResult adds reflector result`() {
        val bundle = makeBundle(25f, 0f, 5f, SlaGrade.GREEN)
        viewModel.updateResult("r1", "US East", bundle)

        assertEquals(1, viewModel.results.value.size)
        val result = viewModel.results.value["r1"]!!
        assertEquals("US East", result.regionName)
        assertEquals(25f, result.avgRttMs)
        assertEquals(SlaGrade.GREEN, result.grade)
    }

    @Test
    fun `overall grade is worst across reflectors`() {
        viewModel.updateResult("r1", "US East", makeBundle(25f, 0f, 5f, SlaGrade.GREEN))
        assertEquals(SlaGrade.GREEN, viewModel.overallGrade.value)

        viewModel.updateResult("r2", "EU West", makeBundle(120f, 0f, 35f, SlaGrade.YELLOW))
        assertEquals(SlaGrade.YELLOW, viewModel.overallGrade.value)

        viewModel.updateResult("r3", "AP Southeast", makeBundle(250f, 3f, 60f, SlaGrade.RED))
        assertEquals(SlaGrade.RED, viewModel.overallGrade.value)
    }

    @Test
    fun `recordFailure marks reflector as RED`() {
        viewModel.recordFailure("r1", "US East")

        val result = viewModel.results.value["r1"]!!
        assertEquals(SlaGrade.RED, result.grade)
        assertEquals(100f, result.lossPct)
        assertEquals(SlaGrade.RED, viewModel.overallGrade.value)
    }

    @Test
    fun `updateResult sets lastTestTime`() {
        assertNull(viewModel.lastTestTime.value)
        viewModel.updateResult("r1", "US East", makeBundle(25f, 0f, 5f, SlaGrade.GREEN))
        assertNotNull(viewModel.lastTestTime.value)
    }

    @Test
    fun `setMeasuring toggles measuring state`() {
        assertFalse(viewModel.isMeasuring.value)
        viewModel.setMeasuring(true)
        assertTrue(viewModel.isMeasuring.value)
        viewModel.setMeasuring(false)
        assertFalse(viewModel.isMeasuring.value)
    }

    @Test
    fun `replacing result for same reflector updates grade`() {
        viewModel.updateResult("r1", "US East", makeBundle(25f, 0f, 5f, SlaGrade.GREEN))
        assertEquals(SlaGrade.GREEN, viewModel.overallGrade.value)

        viewModel.updateResult("r1", "US East", makeBundle(150f, 2f, 40f, SlaGrade.YELLOW))
        assertEquals(SlaGrade.YELLOW, viewModel.overallGrade.value)
    }

    @Test
    fun `multiple reflectors all GREEN gives overall GREEN`() {
        viewModel.updateResult("r1", "A", makeBundle(10f, 0f, 2f, SlaGrade.GREEN))
        viewModel.updateResult("r2", "B", makeBundle(20f, 0f, 3f, SlaGrade.GREEN))
        viewModel.updateResult("r3", "C", makeBundle(15f, 0f, 1f, SlaGrade.GREEN))
        assertEquals(SlaGrade.GREEN, viewModel.overallGrade.value)
    }
}
