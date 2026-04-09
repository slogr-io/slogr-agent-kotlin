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
        val s = store.settings.value
        assertEquals(listOf("gaming", "voip", "streaming"), s.activeProfiles)
        assertEquals(300, s.testIntervalSeconds)
        assertFalse(s.tracerouteEnabled)
        assertTrue(s.autoStartEnabled)
        assertTrue(s.notificationsEnabled)
        assertTrue(s.servers.isEmpty())
    }

    @Test
    fun `save creates settings file`() {
        store.save(DesktopSettings())
        assertTrue(tempDir.resolve("settings.json").exists())
    }

    @Test
    fun `save and load roundtrip preserves all fields`() {
        val custom = DesktopSettings(
            activeProfiles = listOf("gaming", "cloud", "trading"),
            testIntervalSeconds = 120,
            tracerouteEnabled = false,
            servers = listOf(
                ServerEntry("id-1", "10.0.0.1", 862, "Office"),
                ServerEntry("id-2", "10.0.0.2", 9000, "Lab"),
            ),
            autoStartEnabled = false,
            notificationsEnabled = false,
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
        assertEquals("5 min", DesktopSettings.intervalLabel(300))
    }

    @Test
    fun `server entry displayLabel uses label or fallback`() {
        val withLabel = ServerEntry("id", "1.2.3.4", 862, "My Server")
        assertEquals("My Server", withLabel.displayLabel)
        val noLabel = ServerEntry("id", "1.2.3.4", 862, "")
        assertEquals("1.2.3.4:862", noLabel.displayLabel)
    }
}
