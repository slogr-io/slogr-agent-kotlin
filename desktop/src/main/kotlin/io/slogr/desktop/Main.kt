package io.slogr.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.reflectors.NearestSelector
import io.slogr.desktop.core.reflectors.ReflectorCache
import io.slogr.desktop.core.reflectors.ReflectorDiscoveryClient
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import io.slogr.desktop.ui.settings.SettingsWindow
import io.slogr.desktop.ui.theme.SlogrTheme
import io.slogr.desktop.ui.tray.TrayIconGenerator

fun main() = application {
    val dataDir = remember { DataDirectory.resolve() }
    val settingsStore = remember { DesktopSettingsStore(dataDir) }
    val keyStore = remember { EncryptedKeyStore(dataDir) }
    val stateManager = remember { DesktopStateManager(keyStore) }
    val profileManager = remember { ProfileManager(settingsStore, stateManager) }
    val reflectorCache = remember { ReflectorCache(dataDir) }
    val discoveryClient = remember { ReflectorDiscoveryClient(reflectorCache, stateManager) }

    // Initialize on first composition
    LaunchedEffect(Unit) {
        settingsStore.load()
        stateManager.initialize()
        profileManager.initialize(settingsStore.settings.value)
        discoveryClient.discover()

        // Auto-select nearest reflectors if none selected
        val settings = settingsStore.settings.value
        if (settings.selectedReflectorIds.isEmpty()) {
            val all = discoveryClient.reflectors.value
            val accessible = discoveryClient.filterByTier(all)
            val userRegion = discoveryClient.userRegion.value
            if (accessible.isNotEmpty()) {
                // Use first reflector's coordinates as rough user location if no region info
                val nearest = if (userRegion != null) {
                    // Mock: use Karachi as user location for pk-sindh
                    NearestSelector.selectNearest(accessible, 24.8607, 67.0011, maxCount = 3)
                } else {
                    accessible.take(3)
                }
                settingsStore.update { it.copy(selectedReflectorIds = nearest.map { r -> r.id }) }
            }
        }
    }

    var isWindowVisible by remember { mutableStateOf(true) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(size = DpSize(480.dp, 640.dp))
    val settings by settingsStore.settings.collectAsState()
    val agentState by stateManager.state.collectAsState()

    val trayIcon = remember { TrayIconGenerator.greyIcon() }

    Tray(
        icon = trayIcon,
        tooltip = "Slogr",
        onAction = { isWindowVisible = true },
        menu = {
            Item("Open Window", onClick = { isWindowVisible = true })
            Separator()
            Item("Settings...", onClick = { isSettingsOpen = true })
            Separator()
            Item("Quit", onClick = ::exitApplication)
        },
    )

    Window(
        onCloseRequest = {
            if (settings.minimizeToTrayOnClose) {
                isWindowVisible = false
            } else {
                exitApplication()
            }
        },
        visible = isWindowVisible,
        title = "Slogr",
        state = windowState,
    ) {
        window.minimumSize = java.awt.Dimension(400, 500)

        SlogrTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // Main content area (placeholder until Phase 3)
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Slogr Desktop — measurement UI coming in Phase 3",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }

                // Footer
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val stateLabel = when (agentState) {
                        AgentState.ANONYMOUS -> "Not signed in"
                        AgentState.REGISTERED -> "Free"
                        AgentState.CONNECTED -> "Pro"
                    }
                    Text(
                        stateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    TextButton(onClick = { isSettingsOpen = true }) {
                        Text("\u2699 Settings")
                    }
                }
            }
        }
    }

    if (isSettingsOpen) {
        SettingsWindow(
            onClose = { isSettingsOpen = false },
            stateManager = stateManager,
            settingsStore = settingsStore,
            profileManager = profileManager,
            discoveryClient = discoveryClient,
        )
    }
}
