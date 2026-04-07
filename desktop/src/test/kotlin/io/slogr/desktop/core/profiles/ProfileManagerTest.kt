package io.slogr.desktop.core.profiles

import io.slogr.agent.contracts.*
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.datetime.Clock
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class ProfileManagerTest {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var settingsStore: DesktopSettingsStore
    private lateinit var stateManager: DesktopStateManager
    private lateinit var pm: ProfileManager

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-profile-test")
        settingsStore = DesktopSettingsStore(tempDir)
        stateManager = DesktopStateManager(EncryptedKeyStore(tempDir))
        pm = ProfileManager(settingsStore, stateManager)
        settingsStore.load()
        stateManager.initialize()
        pm.initialize(settingsStore.settings.value)
    }

    @AfterTest
    fun tearDown() { tempDir.toFile().deleteRecursively() }

    @Test
    fun `default active profiles are gaming voip streaming`() {
        assertEquals(listOf("gaming", "voip", "streaming"), pm.activeProfiles.value)
    }

    @Test
    fun `8 traffic types defined`() {
        assertEquals(8, ProfileManager.ALL_TRAFFIC_TYPES.size)
    }

    @Test
    fun `free users can access internet gaming voip streaming`() {
        val free = ProfileManager.ALL_TRAFFIC_TYPES.filter { pm.isAvailable(it.name) }
        assertEquals(4, free.size)
        assertTrue(free.map { it.name }.containsAll(listOf("internet", "gaming", "voip", "streaming")))
    }

    @Test
    fun `paid users can access all 8`() {
        stateManager.setApiKey("sk_live_paid")
        val all = ProfileManager.ALL_TRAFFIC_TYPES.filter { pm.isAvailable(it.name) }
        assertEquals(8, all.size)
    }

    @Test
    fun `toggleProfile adds profile when under max`() {
        // Default has 3; remove one first
        pm.toggleProfile("streaming")
        assertEquals(2, pm.activeProfiles.value.size)
        val err = pm.toggleProfile("internet")
        assertNull(err)
        assertTrue("internet" in pm.activeProfiles.value)
    }

    @Test
    fun `toggleProfile returns error when at max`() {
        assertEquals(3, pm.activeProfiles.value.size)
        val err = pm.toggleProfile("internet")
        assertNotNull(err)
        assertTrue(err.contains("Uncheck"))
    }

    @Test
    fun `toggleProfile removes active profile`() {
        val err = pm.toggleProfile("gaming")
        assertNull(err)
        assertFalse("gaming" in pm.activeProfiles.value)
    }

    @Test
    fun `cannot remove last profile`() {
        pm.toggleProfile("gaming")
        pm.toggleProfile("voip")
        // Only streaming left
        val err = pm.toggleProfile("streaming")
        assertNull(err) // returns null but doesn't remove (size check)
        assertEquals(1, pm.activeProfiles.value.size)
    }

    @Test
    fun `evaluateAll returns 3 grades`() {
        val result = MeasurementResult(
            sessionId = UUID.randomUUID(), pathId = UUID.randomUUID(),
            sourceAgentId = UUID.randomUUID(), destAgentId = UUID.randomUUID(),
            srcCloud = "res", srcRegion = "local", dstCloud = "gcp", dstRegion = "us",
            windowTs = Clock.System.now(),
            profile = SlaProfile("test", 10, 50, 2000, 0, 64, rttGreenMs = 100f, rttRedMs = 200f,
                jitterGreenMs = 30f, jitterRedMs = 50f, lossGreenPct = 1f, lossRedPct = 5f),
            fwdMinRttMs = 20f, fwdAvgRttMs = 25f, fwdMaxRttMs = 30f,
            fwdJitterMs = 5f, fwdLossPct = 0f,
            packetsSent = 10, packetsRecv = 10,
        )
        val grades = pm.evaluateAll(result)
        assertEquals(3, grades.size)
        // 25ms RTT, 5ms jitter, 0% loss — should be GREEN for all 3 default profiles
        assertTrue(grades.all { it.grade == SlaGrade.GREEN })
    }

    @Test
    fun `worstGrade returns RED when any RED`() {
        val grades = listOf(
            TrafficGrade(ProfileManager.ALL_TRAFFIC_TYPES[0], SlaGrade.GREEN, 10f, 0f),
            TrafficGrade(ProfileManager.ALL_TRAFFIC_TYPES[1], SlaGrade.RED, 200f, 3f),
            TrafficGrade(ProfileManager.ALL_TRAFFIC_TYPES[2], SlaGrade.GREEN, 20f, 0f),
        )
        assertEquals(SlaGrade.RED, pm.worstGrade(grades))
    }

    @Test
    fun `worstGrade returns null for empty`() {
        assertNull(pm.worstGrade(emptyList()))
    }
}
