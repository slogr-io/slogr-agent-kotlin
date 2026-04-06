package io.slogr.desktop.core.profiles

import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import java.nio.file.Files
import kotlin.test.*

class ProfileManagerTest {

    private lateinit var tempDir: java.nio.file.Path
    private lateinit var settingsStore: DesktopSettingsStore
    private lateinit var stateManager: DesktopStateManager
    private lateinit var profileManager: ProfileManager

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-profile-test")
        settingsStore = DesktopSettingsStore(tempDir)
        stateManager = DesktopStateManager(EncryptedKeyStore(tempDir))
        profileManager = ProfileManager(settingsStore, stateManager)
        settingsStore.load()
        stateManager.initialize()
        profileManager.initialize(settingsStore.settings.value)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `default active profile is internet`() {
        assertEquals("internet", profileManager.activeProfileName.value)
    }

    @Test
    fun `internet is always available for free users`() {
        assertTrue(profileManager.isProfileAvailable("internet"))
    }

    @Test
    fun `free pick profiles are unavailable until chosen`() {
        // No secondFreeProfile set yet
        assertFalse(profileManager.isProfileAvailable("gaming"))
        assertFalse(profileManager.isProfileAvailable("voip"))
        assertFalse(profileManager.isProfileAvailable("streaming"))
    }

    @Test
    fun `setting second free profile makes it available`() {
        profileManager.setSecondFreeProfile("gaming")
        assertTrue(
            profileManager.isProfileAvailable("gaming"),
        )
        // Other picks still locked
        assertFalse(profileManager.isProfileAvailable("voip"))
    }

    @Test
    fun `all profiles available for CONNECTED users`() {
        stateManager.setApiKey("sk_live_paid")

        assertTrue(profileManager.isProfileAvailable("internet"))
        assertTrue(profileManager.isProfileAvailable("gaming"))
        assertTrue(profileManager.isProfileAvailable("voip"))
        assertTrue(profileManager.isProfileAvailable("streaming"))
    }

    @Test
    fun `selectProfile changes active profile`() {
        profileManager.setSecondFreeProfile("voip")
        profileManager.selectProfile("voip")
        assertEquals("voip", profileManager.activeProfileName.value)
    }

    @Test
    fun `selectProfile ignores unavailable profile`() {
        profileManager.selectProfile("gaming") // gaming not set as free pick
        assertEquals("internet", profileManager.activeProfileName.value)
    }

    @Test
    fun `selectProfile persists to settings`() {
        profileManager.setSecondFreeProfile("streaming")
        profileManager.selectProfile("streaming")
        assertEquals("streaming", settingsStore.settings.value.activeProfile)
    }

    @Test
    fun `max free reflectors is 3 for free users`() {
        assertEquals(3, profileManager.maxFreeReflectors())
    }

    @Test
    fun `max reflectors unlimited for paid users`() {
        stateManager.setApiKey("sk_live_paid")
        assertEquals(Int.MAX_VALUE, profileManager.maxFreeReflectors())
    }

    @Test
    fun `custom targets only for paid users`() {
        assertFalse(profileManager.canAddCustomTarget())
        stateManager.setApiKey("sk_live_paid")
        assertTrue(profileManager.canAddCustomTarget())
    }

    @Test
    fun `desktop profiles list has 4 entries`() {
        assertEquals(4, ProfileManager.DESKTOP_PROFILES.size)
        assertEquals(
            listOf("internet", "gaming", "voip", "streaming"),
            ProfileManager.DESKTOP_PROFILES.map { it.name },
        )
    }
}
