package io.slogr.desktop.core.profiles

import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.engine.sla.ProfileRegistry
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop profile metadata — maps engine profile names to desktop display names
 * with freemium tier information.
 */
data class DesktopProfile(
    val name: String,
    val displayName: String,
    val tier: ProfileTier,
)

enum class ProfileTier {
    /** Always available to all users. */
    FREE_ALWAYS,
    /** Free users can pick one of these as their second profile. */
    FREE_PICK,
    /** Paid users only. */
    PAID_ONLY,
}

class ProfileManager(
    private val settingsStore: DesktopSettingsStore,
    private val stateManager: DesktopStateManager,
) {

    companion object {
        val DESKTOP_PROFILES = listOf(
            DesktopProfile("internet", "Internet", ProfileTier.FREE_ALWAYS),
            DesktopProfile("gaming", "Gaming", ProfileTier.FREE_PICK),
            DesktopProfile("voip", "VoIP / Video", ProfileTier.FREE_PICK),
            DesktopProfile("streaming", "Streaming", ProfileTier.FREE_PICK),
        )
    }

    private val _activeProfileName = MutableStateFlow("internet")
    val activeProfileName: StateFlow<String> = _activeProfileName.asStateFlow()

    fun initialize(settings: DesktopSettings) {
        _activeProfileName.value = settings.activeProfile
    }

    fun selectProfile(name: String) {
        val settings = settingsStore.settings.value
        if (!isProfileAvailable(name, settings)) return

        _activeProfileName.value = name
        settingsStore.update { it.copy(activeProfile = name) }
    }

    fun setSecondFreeProfile(name: String) {
        settingsStore.update { it.copy(secondFreeProfile = name) }
    }

    fun getActiveProfile(): SlaProfile? = ProfileRegistry.get(_activeProfileName.value)

    /**
     * Checks if a profile is available given the current state and settings.
     * - Paid users: all profiles available
     * - Free users: "internet" always + one chosen free pick
     */
    fun isProfileAvailable(name: String, settings: DesktopSettings = settingsStore.settings.value): Boolean {
        if (stateManager.state.value == AgentState.CONNECTED) return true

        val profile = DESKTOP_PROFILES.find { it.name == name } ?: return false
        return when (profile.tier) {
            ProfileTier.FREE_ALWAYS -> true
            ProfileTier.FREE_PICK -> name == settings.secondFreeProfile
            ProfileTier.PAID_ONLY -> false
        }
    }

    fun maxFreeReflectors(): Int =
        if (stateManager.state.value == AgentState.CONNECTED) Int.MAX_VALUE else 3

    fun canAddCustomTarget(): Boolean =
        stateManager.state.value == AgentState.CONNECTED
}
