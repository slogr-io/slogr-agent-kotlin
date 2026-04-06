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
import io.slogr.desktop.core.autostart.AutoStartManager
import io.slogr.desktop.core.history.HistoryPruner
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.notifications.DesktopNotifier
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
import java.awt.Desktop
import java.net.URI
import java.util.UUID

/** Entry point. Pass `--background` to start hidden (tray-only). */
fun main(args: Array<String>) {
    val startBackground = "--background" in args

    application {
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
            val s = settingsStore.settings.value
            if (s.selectedReflectorIds.isEmpty()) {
                val accessible = discoveryClient.filterByTier(discoveryClient.reflectors.value)
                if (accessible.isNotEmpty()) {
                    val nearest = NearestSelector.selectNearest(accessible, 24.8607, 67.0011, maxCount = 3)
                    settingsStore.update { it.copy(selectedReflectorIds = nearest.map { r -> r.id }) }
                }
            }
        }

        // Collect state
        val settings by settingsStore.settings.collectAsState()
        val agentState by stateManager.state.collectAsState()
        val allReflectors by discoveryClient.reflectors.collectAsState()
        val overallGrade by viewModel.overallGrade.collectAsState()
        val isMeasuring by viewModel.isMeasuring.collectAsState()
        val results by viewModel.results.collectAsState()
        val lastTestTime by viewModel.lastTestTime.collectAsState()
        val recentHistory by viewModel.recentHistory.collectAsState()
        val activeProfileName by profileManager.activeProfileName.collectAsState()

        // Start/restart scheduler when config changes
        LaunchedEffect(settings.selectedReflectorIds, activeProfileName, settings.testIntervalSeconds) {
            val selectedReflectors = allReflectors.filter { it.id in settings.selectedReflectorIds }
            val profile = ProfileRegistry.get(activeProfileName) ?: return@LaunchedEffect
            if (selectedReflectors.isNotEmpty()) {
                scheduler.start(
                    selectedReflectors, profile,
                    settings.testIntervalSeconds, settings.tracerouteEnabled,
                )
            }
        }

        // Grade-change notifications
        LaunchedEffect(overallGrade) {
            DesktopNotifier.onGradeUpdate(overallGrade, settings.notificationsEnabled)
        }

        // Sync auto-start with settings
        LaunchedEffect(settings.autoStartEnabled) {
            if (settings.autoStartEnabled) AutoStartManager.enable()
            else AutoStartManager.disable()
        }

        // Cleanup on exit
        DisposableEffect(Unit) {
            onDispose {
                scheduler.shutdown()
                historyPruner.stop()
                historyStore.close()
            }
        }

        // --background: start with window hidden
        var isWindowVisible by remember { mutableStateOf(!startBackground) }
        var isSettingsOpen by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(size = DpSize(480.dp, 640.dp))
        val scope = rememberCoroutineScope()

        // Helper: run test now
        val runTestNow: () -> Unit = {
            scope.launch {
                val selected = allReflectors.filter { it.id in settings.selectedReflectorIds }
                val profile = ProfileRegistry.get(activeProfileName) ?: return@launch
                scheduler.runOnce(selected, profile, settings.tracerouteEnabled)
                viewModel.refreshHistory(historyStore)
            }
        }

        // Dynamic tray icon
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

        // Tray with full context menu
        Tray(
            icon = trayIcon,
            tooltip = trayTooltip,
            onAction = { isWindowVisible = true },
            menu = {
                // Status labels
                val gradeLabel = overallGrade?.name ?: "No data"
                Item("Connection: $gradeLabel", enabled = false, onClick = {})
                val timeLabel = if (lastTestTime != null) {
                    val mins = (kotlinx.datetime.Clock.System.now() - lastTestTime!!).inWholeMinutes
                    if (mins < 1) "Last test: just now" else "Last test: $mins min ago"
                } else "No tests yet"
                Item(timeLabel, enabled = false, onClick = {})

                Separator()

                // Monitoring Profile submenu
                Menu("Monitoring Profile") {
                    ProfileManager.DESKTOP_PROFILES.forEach { dp ->
                        val available = profileManager.isProfileAvailable(dp.name)
                        val prefix = if (dp.name == activeProfileName) "\u25CF " else "\u25CB "
                        val suffix = if (!available) " (Pro)" else ""
                        Item("$prefix${dp.displayName}$suffix", enabled = available, onClick = {
                            profileManager.selectProfile(dp.name)
                        })
                    }
                }

                Separator()

                Item(
                    if (isMeasuring) "Testing..." else "Run Test Now",
                    enabled = !isMeasuring,
                    onClick = runTestNow,
                )
                Item("Open Window", onClick = { isWindowVisible = true })

                // Open Dashboard — CONNECTED only
                if (agentState == AgentState.CONNECTED) {
                    Item("Open Dashboard", onClick = {
                        try {
                            Desktop.getDesktop().browse(URI("https://app.slogr.io/dashboard"))
                        } catch (_: Exception) { }
                    })
                }

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
                    Box(modifier = Modifier.weight(1f)) {
                        MainContent(
                            overallGrade = overallGrade,
                            isMeasuring = isMeasuring,
                            results = results,
                            lastTestTime = lastTestTime,
                            recentHistory = recentHistory,
                            onRunTestNow = runTestNow,
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

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (agentState == AgentState.CONNECTED) {
                                TextButton(onClick = {
                                    try {
                                        Desktop.getDesktop().browse(URI("https://app.slogr.io/dashboard"))
                                    } catch (_: Exception) { }
                                }) {
                                    Text("Dashboard")
                                }
                            }
                            TextButton(onClick = { isSettingsOpen = true }) {
                                Text("\u2699 Settings")
                            }
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
}
