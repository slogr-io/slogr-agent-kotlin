package io.slogr.desktop.core.settings

import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.*
import java.nio.file.Files

class DesktopSettingsStoreTest {

    private lateinit var store: DesktopSettingsStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-test")
        store = DesktopSettingsStore(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `load returns defaults when no file exists`() {
        store.load()
        val settings = store.settings.value
        assertEquals("internet", settings.activeProfile)
        assertEquals(300, settings.testIntervalSeconds)
        assertTrue(settings.tracerouteEnabled)
        assertTrue(settings.autoStartEnabled)
        assertTrue(settings.notificationsEnabled)
        assertTrue(settings.minimizeToTrayOnClose)
    }

    @Test
    fun `save creates settings file`() {
        store.save(DesktopSettings())
        assertTrue(tempDir.resolve("settings.json").exists())
    }

    @Test
    fun `save and load roundtrip preserves all fields`() {
        val custom = DesktopSettings(
            activeProfile = "gaming",
            secondFreeProfile = "voip",
            testIntervalSeconds = 120,
            tracerouteEnabled = false,
            selectedReflectorIds = listOf("id-1", "id-2"),
            autoStartEnabled = false,
            notificationsEnabled = false,
            minimizeToTrayOnClose = false,
        )
        store.save(custom)

        val reloaded = DesktopSettingsStore(tempDir)
        reloaded.load()
        assertEquals(custom, reloaded.settings.value)
    }

    @Test
    fun `update modifies specific field`() {
        store.save(DesktopSettings())
        store.update { it.copy(testIntervalSeconds = 60) }
        assertEquals(60, store.settings.value.testIntervalSeconds)

        // Verify persisted
        val reloaded = DesktopSettingsStore(tempDir)
        reloaded.load()
        assertEquals(60, reloaded.settings.value.testIntervalSeconds)
    }

    @Test
    fun `corrupted file falls back to defaults`() {
        tempDir.createDirectories()
        tempDir.resolve("settings.json").toFile().writeText("{{not json}}")
        store.load()
        assertEquals(DesktopSettings(), store.settings.value)
    }

    @Test
    fun `interval labels format correctly`() {
        assertEquals("1 min", DesktopSettings.intervalLabel(60))
        assertEquals("2 min", DesktopSettings.intervalLabel(120))
        assertEquals("5 min", DesktopSettings.intervalLabel(300))
        assertEquals("30 min", DesktopSettings.intervalLabel(1800))
    }
}
