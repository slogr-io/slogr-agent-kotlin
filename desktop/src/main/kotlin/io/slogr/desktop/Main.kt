package io.slogr.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import io.slogr.desktop.core.network.IspDetector
import io.slogr.desktop.core.notifications.DesktopNotifier
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.scheduler.DesktopMeasurementScheduler
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.EncryptedKeyStore
import io.slogr.desktop.core.state.DesktopStateManager
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import io.slogr.desktop.ui.theme.*
import io.slogr.desktop.ui.tray.TrayIconGenerator
import io.slogr.desktop.ui.window.DashboardView
import io.slogr.desktop.ui.window.SettingsView
import kotlinx.coroutines.launch
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.util.UUID
import javax.swing.SwingUtilities

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

    val autoUpdater = io.slogr.desktop.core.update.AutoUpdater()
    val ispDetector = IspDetector()

    settingsStore.load()
    stateManager.initialize()
    profileManager.initialize(settingsStore.settings.value)

    application {
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
        val activeServer = settings.servers.find { it.id == settings.activeServerId } ?: settings.servers.firstOrNull()
        val activeServerList = listOfNotNull(activeServer)

        val updateInfo by autoUpdater.updateAvailable.collectAsState()
        val ispInfo by ispDetector.ispInfo.collectAsState()

        // Compose tray popup
        var showTrayPopup by remember { mutableStateOf(false) }
        var popupX by remember { mutableStateOf(0) }
        var popupY by remember { mutableStateOf(0) }

        val trayIconRef = remember { arrayOfNulls<TrayIcon>(1) }

        val runTestNow: () -> Unit = {
            scope.launch {
                scheduler.runOnce(activeServerList, settings.tracerouteEnabled)
                viewModel.refreshHistory(historyStore)
            }
        }

        // Install AWT tray icon (icon only, no PopupMenu)
        LaunchedEffect(Unit) {
            if (SystemTray.isSupported()) {
                val img = TrayIconGenerator.createAwtImage(Color(0xFF212121), 16)
                val ti = TrayIcon(img, "Slogr").apply {
                    isImageAutoSize = false
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseReleased(e: MouseEvent) {
                            if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
                                popupX = e.xOnScreen; popupY = e.yOnScreen; showTrayPopup = true
                            }
                        }
                        override fun mousePressed(e: MouseEvent) {
                            if (e.isPopupTrigger) {
                                popupX = e.xOnScreen; popupY = e.yOnScreen; showTrayPopup = true
                            }
                        }
                        override fun mouseClicked(e: MouseEvent) {
                            if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) isWindowVisible = true
                        }
                    })
                }
                try { SystemTray.getSystemTray().add(ti) } catch (_: Exception) {}
                trayIconRef[0] = ti
            }
            historyStore.initialize()
            historyPruner.start()
            autoUpdater.start()
            ispDetector.start()
            viewModel.refreshHistory(historyStore)
        }

        // Update tray icon color
        LaunchedEffect(overallGrade, hasServers) {
            val color = when {
                !hasServers -> Color(0xFF212121)
                overallGrade == SlaGrade.GREEN -> SlogrGreen
                overallGrade == SlaGrade.YELLOW -> SlogrYellow
                overallGrade == SlaGrade.RED -> SlogrRed
                else -> SlogrGrey
            }
            trayIconRef[0]?.image = TrayIconGenerator.createAwtImage(color, 16)
            trayIconRef[0]?.toolTip = when {
                !hasServers -> "Slogr - No servers"
                overallGrade != null -> "Slogr - ${overallGrade!!.name}"
                else -> "Slogr"
            }
        }

        LaunchedEffect(overallGrade) { DesktopNotifier.onGradeUpdate(overallGrade, settings.notificationsEnabled) }
        LaunchedEffect(settings.autoStartEnabled) { if (settings.autoStartEnabled) AutoStartManager.enable() else AutoStartManager.disable() }

        LaunchedEffect(activeServer, settings.testIntervalSeconds, settings.tracerouteEnabled) {
            if (activeServer != null) scheduler.start(listOf(activeServer), settings.testIntervalSeconds, settings.tracerouteEnabled)
            else scheduler.stop()
        }

        DisposableEffect(Unit) {
            onDispose {
                scheduler.shutdown(); historyPruner.stop(); autoUpdater.stop(); ispDetector.stop(); historyStore.close()
                trayIconRef[0]?.let { try { SystemTray.getSystemTray().remove(it) } catch (_: Exception) {} }
            }
        }

        // ── Compose tray popup window ──
        if (showTrayPopup) {
            val timeText = if (lastTestTime != null) {
                val mins = (kotlinx.datetime.Clock.System.now() - lastTestTime!!).inWholeMinutes
                if (mins < 1) "just now" else "$mins min ago"
            } else "never"

            Window(
                onCloseRequest = { showTrayPopup = false },
                state = rememberWindowState(
                    position = WindowPosition((popupX - 200).coerceAtLeast(0).dp, (popupY - 230).coerceAtLeast(0).dp),
                    size = DpSize(220.dp, if (hasServers) 230.dp else 180.dp),
                ),
                undecorated = true, transparent = false, resizable = false, focusable = true, alwaysOnTop = true, title = "",
            ) {
                LaunchedEffect(Unit) {
                    window.addWindowFocusListener(object : WindowFocusListener {
                        override fun windowLostFocus(e: WindowEvent?) { showTrayPopup = false }
                        override fun windowGainedFocus(e: WindowEvent?) {}
                    })
                }
                Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 8.dp, color = Color.White) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        if (hasServers) {
                            TrayMenuItem("Status: ${overallGrade?.name ?: "Unknown"}", enabled = false)
                            TrayMenuItem("Last test: $timeText", enabled = false)
                            HorizontalDivider(color = SlogrBorder, modifier = Modifier.padding(vertical = 4.dp))
                            TrayMenuItem(if (isMeasuring) "Testing..." else "Run Test Now", enabled = !isMeasuring) { runTestNow(); showTrayPopup = false }
                            TrayMenuItem("Open Slogr") { isWindowVisible = true; showTrayPopup = false }
                            HorizontalDivider(color = SlogrBorder, modifier = Modifier.padding(vertical = 4.dp))
                            TrayMenuItem("Quit") { exitApplication() }
                        } else {
                            TrayMenuItem("No servers configured", enabled = false)
                            TrayMenuItem("Add a server to start", enabled = false)
                            HorizontalDivider(color = SlogrBorder, modifier = Modifier.padding(vertical = 4.dp))
                            TrayMenuItem("Open Slogr") { isWindowVisible = true; showTrayPopup = false }
                            HorizontalDivider(color = SlogrBorder, modifier = Modifier.padding(vertical = 4.dp))
                            TrayMenuItem("Quit") { exitApplication() }
                        }
                    }
                }
            }
        }

        // ── Main window ──
        if (isWindowVisible) {
            Window(onCloseRequest = { isWindowVisible = false }, title = "Slogr", state = windowState,
                icon = painterResource("slogr-icon.png"),
            ) {
                window.minimumSize = java.awt.Dimension(500, 400)
                SlogrTheme {
                    Column(Modifier.fillMaxSize()) {
                        // Update banner — dismissable with "Later"
                        val currentUpdate = updateInfo
                        if (currentUpdate != null && !currentUpdate.required) {
                            Surface(color = SlogrGreen, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text("Slogr v${currentUpdate.version} is available.",
                                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { autoUpdater.openDownloadPage() },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
                                        ) { Text("Update Now", fontSize = 12.sp) }
                                        TextButton(onClick = { autoUpdater.dismiss() },
                                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.8f)),
                                        ) { Text("Later", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                        // Required update — modal dialog
                        if (currentUpdate != null && currentUpdate.required) {
                            AlertDialog(
                                onDismissRequest = {},
                                title = { Text("Update Required") },
                                text = { Text("Slogr v${currentUpdate.version} is required to continue.\n${currentUpdate.releaseNotes ?: ""}") },
                                confirmButton = {
                                    Button(onClick = { autoUpdater.openDownloadPage() },
                                        colors = ButtonDefaults.buttonColors(containerColor = SlogrGreen),
                                    ) { Text("Update Now") }
                                },
                            )
                        }
                    Row(Modifier.fillMaxSize().weight(1f).background(SlogrBackground)) {
                        Column(Modifier.width(140.dp).fillMaxHeight().background(SlogrSidebarBg).padding(vertical = 12.dp)) {
                            Image(painter = painterResource("slogr-logo.png"), contentDescription = "Slogr",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).width(100.dp))
                            Spacer(Modifier.height(16.dp))
                            listOf(MainView.DASHBOARD to "Dashboard", MainView.SETTINGS to "Settings").forEach { (view, label) ->
                                val sel = view == activeView
                                Text(label, fontSize = 14.sp,
                                    color = if (sel) SlogrDarkGreen else TextSecondary,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (sel) Modifier.background(SlogrSidebarSelected) else Modifier)
                                        .clickable { activeView = view }
                                        .padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            Text("Quit", fontSize = 13.sp, color = TextSecondary,
                                modifier = Modifier.fillMaxWidth().clickable { exitApplication() }.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                        VerticalDivider(color = SlogrBorder)
                        Box(Modifier.fillMaxSize().background(SlogrBackground)) {
                            when (activeView) {
                                MainView.DASHBOARD -> DashboardView(
                                    trafficGrades = trafficGrades, isMeasuring = isMeasuring, lastTestTime = lastTestTime,
                                    hasServers = hasServers, historyStore = historyStore, profileManager = profileManager,
                                    ispInfo = ispInfo, onRunTestNow = runTestNow, onGoToSettings = { activeView = MainView.SETTINGS })
                                MainView.SETTINGS -> SettingsView(
                                    settings = settings, settingsStore = settingsStore,
                                    profileManager = profileManager, viewModel = viewModel,
                                    historyStore = historyStore)
                            }
                        }
                    }  // Row
                    }  // Column
                }  // SlogrTheme
            }  // Window
        }  // if isWindowVisible
    }  // application
}  // main

@Composable
private fun TrayMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Text(text, modifier = Modifier.fillMaxWidth()
        .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (enabled) TextPrimary else TextSecondary, fontSize = 14.sp)
}
