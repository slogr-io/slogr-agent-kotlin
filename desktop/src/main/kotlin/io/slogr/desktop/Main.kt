package io.slogr.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.engine.MeasurementEngineImpl
import io.slogr.agent.engine.asn.NullAsnResolver
import io.slogr.agent.native.JavaUdpTransport
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.autostart.AutoStartManager
import io.slogr.desktop.core.history.HistoryPruner
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.notifications.DesktopNotifier
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.scheduler.DesktopMeasurementScheduler
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import io.slogr.desktop.ui.theme.SlogrTheme
import io.slogr.desktop.ui.tray.SlogrTrayManager
import io.slogr.desktop.ui.window.DashboardView
import io.slogr.desktop.ui.window.SettingsView
import kotlinx.coroutines.launch
import java.util.UUID

enum class MainView { DASHBOARD, SETTINGS }

fun main() {
    val dataDir = DataDirectory.resolve()
    val settingsStore = DesktopSettingsStore(dataDir)
    val keyStore = EncryptedKeyStore(dataDir)
    val stateManager = DesktopStateManager(keyStore)
    val profileManager = ProfileManager(settingsStore, stateManager)
    val viewModel = DesktopAgentViewModel()
    val historyStore = LocalHistoryStore(dataDir)
    val historyPruner = HistoryPruner(historyStore)
    val engine = MeasurementEngineImpl(
        adapter = JavaUdpTransport(),
        asnResolver = NullAsnResolver(),
        agentId = UUID.randomUUID(),
    )
    val scheduler = DesktopMeasurementScheduler(engine, viewModel, profileManager, historyStore)

    // Initialize synchronous state
    settingsStore.load()
    stateManager.initialize()
    profileManager.initialize(settingsStore.settings.value)

    application {
        // Tray-only: window hidden by default (B2)
        var isWindowVisible by remember { mutableStateOf(false) }
        var activeView by remember { mutableStateOf(MainView.DASHBOARD) }
        val windowState = rememberWindowState(size = DpSize(620.dp, 520.dp))
        val scope = rememberCoroutineScope()

        val settings by settingsStore.settings.collectAsState()
        val overallGrade by viewModel.overallGrade.collectAsState()
        val isMeasuring by viewModel.isMeasuring.collectAsState()
        val trafficGrades by viewModel.trafficGrades.collectAsState()
        val lastTestTime by viewModel.lastTestTime.collectAsState()
        val recentHistory by viewModel.recentHistory.collectAsState()

        val hasServers = settings.servers.isNotEmpty()

        // AWT tray manager (B1 + R7)
        val trayManager = remember {
            SlogrTrayManager(
                onOpenWindow = { isWindowVisible = true },
                onRunTest = {
                    scope.launch {
                        scheduler.runOnce(settings.servers, settings.tracerouteEnabled)
                        viewModel.refreshHistory(historyStore)
                    }
                },
                onQuit = { exitApplication() },
            )
        }

        // Install tray on first composition
        LaunchedEffect(Unit) {
            trayManager.install()
            historyStore.initialize()
            historyPruner.start()
            viewModel.refreshHistory(historyStore)
        }

        // Update tray after each state change
        LaunchedEffect(overallGrade, hasServers, isMeasuring, lastTestTime) {
            val timeText = if (lastTestTime != null) {
                val mins = (kotlinx.datetime.Clock.System.now() - lastTestTime!!).inWholeMinutes
                if (mins < 1) "Last test: just now" else "Last test: $mins min ago"
            } else "No tests yet"
            trayManager.update(overallGrade, hasServers, isMeasuring, timeText)
        }

        // Notifications
        LaunchedEffect(overallGrade) {
            DesktopNotifier.onGradeUpdate(overallGrade, settings.notificationsEnabled)
        }

        // Auto-start sync
        LaunchedEffect(settings.autoStartEnabled) {
            if (settings.autoStartEnabled) AutoStartManager.enable()
            else AutoStartManager.disable()
        }

        // Start/restart scheduler when servers or interval change
        LaunchedEffect(settings.servers, settings.testIntervalSeconds, settings.tracerouteEnabled) {
            if (settings.servers.isNotEmpty()) {
                scheduler.start(settings.servers, settings.testIntervalSeconds, settings.tracerouteEnabled)
            } else {
                scheduler.stop()
            }
        }

        // Cleanup
        DisposableEffect(Unit) {
            onDispose {
                scheduler.shutdown()
                historyPruner.stop()
                historyStore.close()
                trayManager.remove()
            }
        }

        // Run test helper
        val runTestNow: () -> Unit = {
            scope.launch {
                scheduler.runOnce(settings.servers, settings.tracerouteEnabled)
                viewModel.refreshHistory(historyStore)
            }
        }

        // Window
        if (isWindowVisible) {
            Window(
                onCloseRequest = { isWindowVisible = false },
                title = "Slogr",
                state = windowState,
            ) {
                window.minimumSize = java.awt.Dimension(500, 400)

                SlogrTheme {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        // Sidebar
                        Column(
                            modifier = Modifier
                                .width(140.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(vertical = 16.dp),
                        ) {
                            // Logo placeholder (R6)
                            Text(
                                "SLOGR",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )

                            Spacer(Modifier.height(16.dp))

                            listOf(MainView.DASHBOARD to "Dashboard", MainView.SETTINGS to "Settings").forEach { (view, label) ->
                                val isSelected = view == activeView
                                Text(
                                    text = label,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeView = view }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }

                        VerticalDivider(color = MaterialTheme.colorScheme.outline)

                        // Content area
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (activeView) {
                                MainView.DASHBOARD -> DashboardView(
                                    trafficGrades = trafficGrades,
                                    isMeasuring = isMeasuring,
                                    lastTestTime = lastTestTime,
                                    recentHistory = recentHistory,
                                    hasServers = hasServers,
                                    onRunTestNow = runTestNow,
                                    onGoToSettings = { activeView = MainView.SETTINGS },
                                )
                                MainView.SETTINGS -> SettingsView(
                                    settings = settings,
                                    settingsStore = settingsStore,
                                    profileManager = profileManager,
                                    viewModel = viewModel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
