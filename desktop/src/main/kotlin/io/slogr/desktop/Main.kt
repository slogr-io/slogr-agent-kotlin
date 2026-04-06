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
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.engine.MeasurementEngineImpl
import io.slogr.agent.engine.asn.NullAsnResolver
import io.slogr.agent.engine.sla.ProfileRegistry
import io.slogr.agent.native.JavaUdpTransport
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.history.HistoryPruner
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.reflectors.NearestSelector
import io.slogr.desktop.core.reflectors.ReflectorCache
import io.slogr.desktop.core.reflectors.ReflectorDiscoveryClient
import io.slogr.desktop.core.scheduler.DesktopMeasurementScheduler
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import io.slogr.desktop.ui.settings.SettingsWindow
import io.slogr.desktop.ui.theme.SlogrTheme
import io.slogr.desktop.ui.tray.TrayIconGenerator
import io.slogr.desktop.ui.window.MainContent
import kotlinx.coroutines.launch
import java.util.UUID

fun main() = application {
    val dataDir = remember { DataDirectory.resolve() }
    val settingsStore = remember { DesktopSettingsStore(dataDir) }
    val keyStore = remember { EncryptedKeyStore(dataDir) }
    val stateManager = remember { DesktopStateManager(keyStore) }
    val profileManager = remember { ProfileManager(settingsStore, stateManager) }
    val reflectorCache = remember { ReflectorCache(dataDir) }
    val discoveryClient = remember { ReflectorDiscoveryClient(reflectorCache, stateManager) }
    val viewModel = remember { DesktopAgentViewModel() }
    val historyStore = remember { LocalHistoryStore(dataDir) }
    val historyPruner = remember { HistoryPruner(historyStore) }

    // Engine: pure-Java mode (no JNI)
    val engine = remember {
        MeasurementEngineImpl(
            adapter = JavaUdpTransport(),
            asnResolver = NullAsnResolver(),
            agentId = UUID.randomUUID(),
        )
    }
    val scheduler = remember { DesktopMeasurementScheduler(engine, viewModel, historyStore) }

    // Initialize
    LaunchedEffect(Unit) {
        settingsStore.load()
        stateManager.initialize()
        profileManager.initialize(settingsStore.settings.value)
        historyStore.initialize()
        historyPruner.start()
        viewModel.refreshHistory(historyStore)
        discoveryClient.discover()

        // Auto-select nearest reflectors if none selected
        val settings = settingsStore.settings.value
        if (settings.selectedReflectorIds.isEmpty()) {
            val accessible = discoveryClient.filterByTier(discoveryClient.reflectors.value)
            if (accessible.isNotEmpty()) {
                val nearest = NearestSelector.selectNearest(accessible, 24.8607, 67.0011, maxCount = 3)
                settingsStore.update { it.copy(selectedReflectorIds = nearest.map { r -> r.id }) }
            }
        }
    }

    // Start/restart scheduler when settings or reflectors change
    val settings by settingsStore.settings.collectAsState()
    val agentState by stateManager.state.collectAsState()
    val allReflectors by discoveryClient.reflectors.collectAsState()
    val overallGrade by viewModel.overallGrade.collectAsState()
    val isMeasuring by viewModel.isMeasuring.collectAsState()
    val results by viewModel.results.collectAsState()
    val lastTestTime by viewModel.lastTestTime.collectAsState()
    val recentHistory by viewModel.recentHistory.collectAsState()
    val activeProfileName by profileManager.activeProfileName.collectAsState()

    LaunchedEffect(settings.selectedReflectorIds, activeProfileName, settings.testIntervalSeconds) {
        val selectedReflectors = allReflectors.filter { it.id in settings.selectedReflectorIds }
        val profile = ProfileRegistry.get(activeProfileName) ?: return@LaunchedEffect
        if (selectedReflectors.isNotEmpty()) {
            scheduler.start(selectedReflectors, profile, settings.testIntervalSeconds, settings.tracerouteEnabled)
        }
    }

    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            scheduler.shutdown()
            historyPruner.stop()
            historyStore.close()
        }
    }

    var isWindowVisible by remember { mutableStateOf(true) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(size = DpSize(480.dp, 640.dp))
    val scope = rememberCoroutineScope()

    // Dynamic tray icon based on grade
    val trayIcon = remember(overallGrade) {
        when (overallGrade) {
            SlaGrade.GREEN -> TrayIconGenerator.greenIcon()
            SlaGrade.YELLOW -> TrayIconGenerator.yellowIcon()
            SlaGrade.RED -> TrayIconGenerator.redIcon()
            null -> TrayIconGenerator.greyIcon()
        }
    }
    val trayTooltip = when (overallGrade) {
        SlaGrade.GREEN -> "Slogr \u2014 Connection quality: GREEN"
        SlaGrade.YELLOW -> "Slogr \u2014 Connection quality: YELLOW"
        SlaGrade.RED -> "Slogr \u2014 Connection quality: RED"
        null -> if (isMeasuring) "Slogr \u2014 Measuring..." else "Slogr"
    }

    Tray(
        icon = trayIcon,
        tooltip = trayTooltip,
        onAction = { isWindowVisible = true },
        menu = {
            Item("Open Window", onClick = { isWindowVisible = true })
            Item("Run Test Now", enabled = !isMeasuring, onClick = {
                scope.launch {
                    val selected = allReflectors.filter { it.id in settings.selectedReflectorIds }
                    val profile = ProfileRegistry.get(activeProfileName) ?: return@launch
                    scheduler.runOnce(selected, profile, settings.tracerouteEnabled)
                    viewModel.refreshHistory(historyStore)
                }
            })
            Separator()
            Item("Settings...", onClick = { isSettingsOpen = true })
            Separator()
            Item("Quit", onClick = ::exitApplication)
        },
    )

    Window(
        onCloseRequest = {
            if (settings.minimizeToTrayOnClose) isWindowVisible = false
            else exitApplication()
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
                // Main content
                Box(modifier = Modifier.weight(1f)) {
                    MainContent(
                        overallGrade = overallGrade,
                        isMeasuring = isMeasuring,
                        results = results,
                        lastTestTime = lastTestTime,
                        recentHistory = recentHistory,
                        onRunTestNow = {
                            scope.launch {
                                val selected = allReflectors.filter { it.id in settings.selectedReflectorIds }
                                val profile = ProfileRegistry.get(activeProfileName) ?: return@launch
                                scheduler.runOnce(selected, profile, settings.tracerouteEnabled)
                    viewModel.refreshHistory(historyStore)
                            }
                        },
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
