package io.slogr.desktop.core.viewmodel

import io.slogr.agent.contracts.*
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.profiles.TrafficGrade
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DesktopAgentViewModelTest {

    private lateinit var viewModel: DesktopAgentViewModel
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-vm-test")
        viewModel = DesktopAgentViewModel()
    }

    @AfterTest
    fun tearDown() { tempDir.toFile().deleteRecursively() }

    @Test
    fun `initial state is empty`() {
        assertTrue(viewModel.serverResults.value.isEmpty())
        assertTrue(viewModel.trafficGrades.value.isEmpty())
        assertNull(viewModel.overallGrade.value)
        assertFalse(viewModel.isMeasuring.value)
    }

    @Test
    fun `clearTrafficGrades sets all to pending`() {
        val types = ProfileManager.ALL_TRAFFIC_TYPES.take(3)
        viewModel.clearTrafficGrades(types)
        assertEquals(3, viewModel.trafficGrades.value.size)
        assertTrue(viewModel.trafficGrades.value.values.all { it.grade == null })
    }

    @Test
    fun `updateTrafficGrade replaces pending with result`() {
        val tt = ProfileManager.ALL_TRAFFIC_TYPES.first()
        viewModel.clearTrafficGrades(listOf(tt))
        assertNull(viewModel.trafficGrades.value[tt.name]?.grade)
        viewModel.updateTrafficGrade(tt.name, TrafficGrade(tt, SlaGrade.GREEN, 25f, 0f))
        assertEquals(SlaGrade.GREEN, viewModel.trafficGrades.value[tt.name]?.grade)
    }

    @Test
    fun `setMeasuring toggles state`() {
        viewModel.setMeasuring(true)
        assertTrue(viewModel.isMeasuring.value)
        viewModel.setMeasuring(false)
        assertFalse(viewModel.isMeasuring.value)
    }
}
