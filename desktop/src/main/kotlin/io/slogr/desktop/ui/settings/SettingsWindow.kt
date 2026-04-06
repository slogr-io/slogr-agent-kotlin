package io.slogr.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.reflectors.ReflectorDiscoveryClient
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.state.DesktopStateManager
import io.slogr.desktop.ui.theme.SlogrTheme

enum class SettingsTab(val label: String) {
    ACCOUNT("Account"),
    MONITORING("Monitoring"),
    LOCATIONS("Locations"),
    APPLICATION("Application"),
    ABOUT("About"),
}

@Composable
fun SettingsWindow(
    onClose: () -> Unit,
    stateManager: DesktopStateManager,
    settingsStore: DesktopSettingsStore,
    profileManager: ProfileManager,
    discoveryClient: ReflectorDiscoveryClient,
) {
    Window(
        onCloseRequest = onClose,
        title = "Slogr — Settings",
        state = rememberWindowState(size = DpSize(640.dp, 520.dp)),
    ) {
        SlogrTheme {
            var selectedTab by remember { mutableStateOf(SettingsTab.ACCOUNT) }
            val settings by settingsStore.settings.collectAsState()
            val agentState by stateManager.state.collectAsState()

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // Left navigation
                Column(
                    modifier = Modifier
                        .width(160.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 12.dp),
                ) {
                    SettingsTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        Text(
                            text = tab.label,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outline,
                )

                // Right content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (selectedTab) {
                        SettingsTab.ACCOUNT -> AccountSection(
                            agentState = agentState,
                            onSetApiKey = { stateManager.setApiKey(it) },
                            onClearApiKey = { stateManager.clearApiKey() },
                        )
                        SettingsTab.MONITORING -> MonitoringSection(
                            settings = settings,
                            agentState = agentState,
                            profileManager = profileManager,
                            onUpdateSettings = { settingsStore.save(it) },
                        )
                        SettingsTab.LOCATIONS -> LocationsSection(
                            discoveryClient = discoveryClient,
                            settingsStore = settingsStore,
                            agentState = agentState,
                        )
                        SettingsTab.APPLICATION -> ApplicationSection(
                            settings = settings,
                            onUpdateSettings = { settingsStore.save(it) },
                        )
                        SettingsTab.ABOUT -> AboutSection()
                    }
                }
            }
        }
    }
}
