package io.slogr.desktop.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.diagnostics.DiagnosticResult
import io.slogr.desktop.core.diagnostics.DiagnosticsRunner
import io.slogr.desktop.core.export.ResultsExporter
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.ServerEntry
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import io.slogr.desktop.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    settings: DesktopSettings,
    settingsStore: DesktopSettingsStore,
    profileManager: ProfileManager,
    viewModel: DesktopAgentViewModel,
    historyStore: LocalHistoryStore? = null,
) {
    val activeProfiles by profileManager.activeProfiles.collectAsState()
    val serverResults by viewModel.serverResults.collectAsState()
    val scope = rememberCoroutineScope()

    // Accordion state — which sections are expanded
    var trafficExpanded by remember { mutableStateOf(false) }
    var serversExpanded by remember { mutableStateOf(false) }
    var appExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        cursorColor = SlogrGreen, focusedBorderColor = SlogrGreen, unfocusedBorderColor = FieldBorder,
        focusedLabelColor = SlogrGreen, unfocusedLabelColor = TextSecondary,
        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // ═══ Traffic Types ═══
        AccordionHeader("Traffic Types", trafficExpanded) { trafficExpanded = !trafficExpanded }
        if (trafficExpanded) {
            TrafficTypesContent(settings, settingsStore, profileManager, activeProfiles, fieldColors)
        }
        HorizontalDivider(color = SlogrBorder)

        // ═══ Servers ═══
        AccordionHeader("Servers", serversExpanded) { serversExpanded = !serversExpanded }
        if (serversExpanded) {
            ServersContent(settings, settingsStore, serverResults, fieldColors)
        }
        HorizontalDivider(color = SlogrBorder)

        // ═══ Application ═══
        AccordionHeader("Application", appExpanded) { appExpanded = !appExpanded }
        if (appExpanded) {
            ApplicationContent(settings, settingsStore)
        }
        HorizontalDivider(color = SlogrBorder)

        // ═══ About ═══
        AccordionHeader("About", aboutExpanded) { aboutExpanded = !aboutExpanded }
        if (aboutExpanded) {
            AboutContent(historyStore, scope)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Accordion header ─────────────────────────────────────────────────

@Composable
private fun AccordionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text(if (expanded) "\u25B2" else "\u25BC", fontSize = 12.sp, color = TextSecondary)
    }
}

// ── Traffic Types section ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrafficTypesContent(
    settings: DesktopSettings, settingsStore: DesktopSettingsStore,
    profileManager: ProfileManager, activeProfiles: List<String>,
    fieldColors: TextFieldColors,
) {
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var typesExpanded by remember { mutableStateOf(false) }

    val selectedTypes = ProfileManager.ALL_TRAFFIC_TYPES.filter { it.name in activeProfiles }
    val unselectedTypes = ProfileManager.ALL_TRAFFIC_TYPES.filter { it.name !in activeProfiles }

    Column(Modifier.padding(start = 8.dp, bottom = 16.dp)) {
        if (errorMsg != null) {
            Text(errorMsg!!, color = SlogrRed, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }

        selectedTypes.forEach { tt ->
            TrafficCheckbox(tt.icon, tt.displayName, checked = true) { errorMsg = profileManager.toggleProfile(tt.name) }
        }
        if (typesExpanded) {
            unselectedTypes.forEach { tt ->
                TrafficCheckbox(tt.icon, tt.displayName, checked = false) { errorMsg = profileManager.toggleProfile(tt.name) }
            }
            TextButton(onClick = { typesExpanded = false }) { Text("Show fewer", color = SlogrGreen) }
        } else if (unselectedTypes.isNotEmpty()) {
            TextButton(onClick = { typesExpanded = true }) { Text("Show all types (${unselectedTypes.size} more)", color = SlogrGreen) }
        }

        Spacer(Modifier.height(12.dp))

        var intervalExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = intervalExpanded, onExpandedChange = { intervalExpanded = it }) {
            OutlinedTextField(
                value = "Test Interval: ${DesktopSettings.intervalLabel(settings.testIntervalSeconds)}",
                onValueChange = {}, readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                colors = fieldColors,
            )
            ExposedDropdownMenu(expanded = intervalExpanded, onDismissRequest = { intervalExpanded = false }) {
                DesktopSettings.TEST_INTERVALS.forEach { seconds ->
                    DropdownMenuItem(
                        text = { Text(DesktopSettings.intervalLabel(seconds), color = TextPrimary) },
                        onClick = { intervalExpanded = false; settingsStore.update { it.copy(testIntervalSeconds = seconds) } },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.tracerouteEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(tracerouteEnabled = it) } },
                colors = CheckboxDefaults.colors(checkedColor = SlogrGreen, uncheckedColor = FieldBorder, checkmarkColor = Color.White))
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Include traceroute", style = MaterialTheme.typography.bodyMedium)
                Text("Adds path trace to each test (increases test time significantly)",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

// ── Servers section ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersContent(
    settings: DesktopSettings, settingsStore: DesktopSettingsStore,
    serverResults: Map<String, io.slogr.desktop.core.viewmodel.ServerResult>,
    fieldColors: TextFieldColors,
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newHost by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("862") }
    var newLabel by remember { mutableStateOf("") }

    Column(Modifier.padding(start = 8.dp, bottom = 16.dp)) {
        if (settings.servers.isEmpty()) {
            Text("No servers added yet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            // Active server dropdown
            val activeServer = settings.servers.find { it.id == settings.activeServerId } ?: settings.servers.first()
            var serverDropExpanded by remember { mutableStateOf(false) }
            Text("Active server:", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            ExposedDropdownMenuBox(expanded = serverDropExpanded, onExpandedChange = { serverDropExpanded = it }) {
                OutlinedTextField(
                    value = activeServer.displayLabel, onValueChange = {}, readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverDropExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    colors = fieldColors,
                )
                ExposedDropdownMenu(expanded = serverDropExpanded, onDismissRequest = { serverDropExpanded = false }) {
                    settings.servers.forEach { server ->
                        DropdownMenuItem(
                            text = { Text("${server.displayLabel} (${server.host}:${server.port})", color = TextPrimary) },
                            onClick = { serverDropExpanded = false; settingsStore.update { it.copy(activeServerId = server.id) } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            settings.servers.forEach { server ->
                val sr = serverResults[server.id]
                val isActive = server.id == activeServer.id
                val dotColor = when { sr == null -> SlogrGrey.copy(alpha = 0.4f); sr.reachable -> SlogrGreen; else -> SlogrRed }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(dotColor, shape = CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.displayLabel + if (isActive) " (active)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActive) SlogrDarkGreen else TextPrimary)
                        Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        settingsStore.update { s ->
                            val newServers = s.servers.filter { it.id != server.id }
                            s.copy(servers = newServers, activeServerId = if (s.activeServerId == server.id) newServers.firstOrNull()?.id else s.activeServerId)
                        }
                    }) { Text("x", color = SlogrRed) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (showAddForm) {
            OutlinedTextField(value = newHost, onValueChange = { newHost = it },
                label = { Text("IP address or hostname") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = newPort, onValueChange = { newPort = it },
                    label = { Text("Port") }, singleLine = true, modifier = Modifier.width(100.dp), colors = fieldColors)
                OutlinedTextField(value = newLabel, onValueChange = { newLabel = it },
                    label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (newHost.isNotBlank()) {
                        val entry = ServerEntry(UUID.randomUUID().toString(), newHost.trim(), newPort.toIntOrNull() ?: 862, newLabel.trim())
                        settingsStore.update { s ->
                            val updated = s.copy(servers = s.servers + entry)
                            if (s.activeServerId == null) updated.copy(activeServerId = entry.id) else updated
                        }
                        newHost = ""; newPort = "862"; newLabel = ""; showAddForm = false
                    }
                }, enabled = newHost.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = SlogrGreen, contentColor = Color.White),
                ) { Text("Add") }
                OutlinedButton(onClick = { showAddForm = false }) { Text("Cancel", color = TextSecondary) }
            }
        } else {
            OutlinedButton(onClick = { showAddForm = true },
                border = androidx.compose.foundation.BorderStroke(1.dp, SlogrGreen),
            ) { Text("+ Add Server", color = SlogrGreen) }
        }
    }
}

// ── Application section ──────────────────────────────────────────────

@Composable
private fun ApplicationContent(settings: DesktopSettings, settingsStore: DesktopSettingsStore) {
    Column(Modifier.padding(start = 8.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.autoStartEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(autoStartEnabled = it) } },
                colors = CheckboxDefaults.colors(checkedColor = SlogrGreen, uncheckedColor = FieldBorder, checkmarkColor = Color.White))
            Spacer(Modifier.width(4.dp))
            Text("Start Slogr on login", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.notificationsEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(notificationsEnabled = it) } },
                colors = CheckboxDefaults.colors(checkedColor = SlogrGreen, uncheckedColor = FieldBorder, checkmarkColor = Color.White))
            Spacer(Modifier.width(4.dp))
            Text("Show notifications", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text("Data: ${DataDirectory.resolve()}", style = MaterialTheme.typography.bodySmall)
    }
}

// ── About section ────────────────────────────────────────────────────

@Composable
private fun AboutContent(historyStore: LocalHistoryStore?, scope: kotlinx.coroutines.CoroutineScope) {
    var diagnosticResults by remember { mutableStateOf<List<DiagnosticResult>?>(null) }
    var isRunningDiag by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    Column(Modifier.padding(start = 8.dp, bottom = 16.dp)) {
        Text("Slogr Desktop v1.1.0", style = MaterialTheme.typography.bodyMedium)
        Text("slogr.io", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                isRunningDiag = true
                scope.launch { diagnosticResults = DiagnosticsRunner.runAll(); isRunningDiag = false }
            }, enabled = !isRunningDiag,
                border = androidx.compose.foundation.BorderStroke(1.dp, SlogrGreen),
            ) { Text(if (isRunningDiag) "Running..." else "Run Diagnostics", color = SlogrGreen) }

            // Share Results button
            if (historyStore != null) {
                OutlinedButton(onClick = {
                    isExporting = true
                    exportStatus = null
                    scope.launch {
                        val chooser = JFileChooser().apply {
                            dialogTitle = "Save Slogr Results"
                            fileFilter = FileNameExtensionFilter("ZIP files", "zip")
                            selectedFile = File("slogr-results-${System.currentTimeMillis() / 1000}.zip")
                        }
                        val result = chooser.showSaveDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            var file = chooser.selectedFile
                            if (!file.name.endsWith(".zip")) file = File(file.absolutePath + ".zip")
                            val ok = ResultsExporter.export(historyStore, file)
                            exportStatus = if (ok) "Saved to ${file.name}" else "Export failed"
                        }
                        isExporting = false
                    }
                }, enabled = !isExporting,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SlogrGreen),
                ) { Text(if (isExporting) "Exporting..." else "Share Results", color = SlogrGreen) }
            }
        }

        if (exportStatus != null) {
            Spacer(Modifier.height(6.dp))
            Text(exportStatus!!, style = MaterialTheme.typography.bodySmall, color = SlogrGreen)
        }

        if (diagnosticResults != null) {
            Spacer(Modifier.height(8.dp))
            diagnosticResults!!.forEach { r ->
                Text("${if (r.passed) "[OK]" else "[FAIL]"} ${r.name}: ${r.detail}",
                    style = MaterialTheme.typography.bodySmall, color = if (r.passed) SlogrGreen else SlogrRed)
            }
        }
    }
}

// ── Checkbox helper ──────────────────────────────────────────────────

@Composable
private fun TrafficCheckbox(icon: String, label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = SlogrGreen, uncheckedColor = FieldBorder, checkmarkColor = Color.White))
        Spacer(Modifier.width(4.dp))
        Text("$icon $label", style = MaterialTheme.typography.bodyMedium)
    }
}
