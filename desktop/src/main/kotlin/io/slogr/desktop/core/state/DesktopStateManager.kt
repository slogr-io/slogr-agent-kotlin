package io.slogr.desktop.core.state

import io.slogr.agent.platform.config.AgentState
import io.slogr.agent.platform.config.determineState
import io.slogr.desktop.core.settings.EncryptedKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopStateManager(private val keyStore: EncryptedKeyStore) {

    private val _apiKey = MutableStateFlow<String?>(null)
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    private val _state = MutableStateFlow(AgentState.ANONYMOUS)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /**
     * Load stored key and env var, determine initial state.
     * Env var takes precedence (supports enterprise IT deployment via SCCM/Intune/Jamf).
     */
    fun initialize() {
        val key = System.getenv("SLOGR_API_KEY") ?: keyStore.load()
        _apiKey.value = key
        _state.value = determineState(key)
    }

    fun setApiKey(key: String) {
        keyStore.store(key)
        _apiKey.value = key
        _state.value = determineState(key)
    }

    fun clearApiKey() {
        keyStore.delete()
        _apiKey.value = null
        _state.value = AgentState.ANONYMOUS
    }
}
