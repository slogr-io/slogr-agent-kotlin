package io.slogr.desktop.core.state

import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.settings.EncryptedKeyStore
import java.nio.file.Files
import kotlin.test.*

class DesktopStateManagerTest {

    private lateinit var keyStore: EncryptedKeyStore
    private lateinit var stateManager: DesktopStateManager
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("slogr-state-test")
        keyStore = EncryptedKeyStore(tempDir)
        stateManager = DesktopStateManager(keyStore)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `initial state is ANONYMOUS`() {
        assertEquals(AgentState.ANONYMOUS, stateManager.state.value)
        assertNull(stateManager.apiKey.value)
    }

    @Test
    fun `initialize with no stored key stays ANONYMOUS`() {
        stateManager.initialize()
        assertEquals(AgentState.ANONYMOUS, stateManager.state.value)
    }

    @Test
    fun `initialize with stored free key becomes REGISTERED`() {
        keyStore.store("sk_free_abc")
        stateManager.initialize()
        assertEquals(AgentState.REGISTERED, stateManager.state.value)
        assertEquals("sk_free_abc", stateManager.apiKey.value)
    }

    @Test
    fun `initialize with stored live key becomes CONNECTED`() {
        keyStore.store("sk_live_xyz")
        stateManager.initialize()
        assertEquals(AgentState.CONNECTED, stateManager.state.value)
        assertEquals("sk_live_xyz", stateManager.apiKey.value)
    }

    @Test
    fun `setApiKey transitions state`() {
        stateManager.initialize()
        assertEquals(AgentState.ANONYMOUS, stateManager.state.value)

        stateManager.setApiKey("sk_free_new")
        assertEquals(AgentState.REGISTERED, stateManager.state.value)

        stateManager.setApiKey("sk_live_upgrade")
        assertEquals(AgentState.CONNECTED, stateManager.state.value)
    }

    @Test
    fun `clearApiKey returns to ANONYMOUS`() {
        stateManager.setApiKey("sk_live_test")
        assertEquals(AgentState.CONNECTED, stateManager.state.value)

        stateManager.clearApiKey()
        assertEquals(AgentState.ANONYMOUS, stateManager.state.value)
        assertNull(stateManager.apiKey.value)
    }

    @Test
    fun `setApiKey persists across instances`() {
        stateManager.setApiKey("sk_live_persist")

        // New instance with same data dir
        val newManager = DesktopStateManager(EncryptedKeyStore(tempDir))
        newManager.initialize()
        assertEquals(AgentState.CONNECTED, newManager.state.value)
        assertEquals("sk_live_persist", newManager.apiKey.value)
    }

    @Test
    fun `invalid key format becomes ANONYMOUS`() {
        stateManager.setApiKey("not_a_valid_key")
        assertEquals(AgentState.ANONYMOUS, stateManager.state.value)
    }
}
