package io.slogr.desktop.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.diagnostics.DiagnosticResult
import io.slogr.desktop.core.diagnostics.DiagnosticsRunner
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.ServerEntry
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import io.slogr.agent.contracts.SlaGrade
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    settings: DesktopSettings,
    settingsStore: DesktopSettingsStore,
    profileManager: ProfileManager,
    viewModel: DesktopAgentViewModel,
) {
    val activeProfiles by profileManager.activeProfiles.collectAsState()
    val serverResults by viewModel.serverResults.collectAsState()
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showAddForm by remember { mutableStateOf(false) }
    var newHost by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("862") }
    var newLabel by remember { mutableStateOf("") }
    var diagnosticResults by remember { mutableStateOf<List<DiagnosticResult>?>(null) }
    var isRunningDiag by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // ── Traffic Types ─────────────────────────────────────────────
        Text("Traffic Types", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Select up to ${DesktopSettings.MAX_ACTIVE_PROFILES}:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(8.dp))

        if (errorMsg != null) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }

        ProfileManager.ALL_TRAFFIC_TYPES.forEach { tt ->
            val isActive = tt.name in activeProfiles
            val available = profileManager.isAvailable(tt.name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isActive,
                    enabled = available || isActive,
                    onCheckedChange = {
                        val msg = profileManager.toggleProfile(tt.name)
                        errorMsg = msg
                    },
                )
                Spacer(Modifier.width(4.dp))
                val suffix = if (!available && !isActive) " (Pro)" else ""
                Text(
                    "${tt.displayName}$suffix",
                    color = if (available || isActive) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Test interval
        var intervalExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = intervalExpanded,
            onExpandedChange = { intervalExpanded = it },
        ) {
            OutlinedTextField(
                value = "Test Interval: ${DesktopSettings.intervalLabel(settings.testIntervalSeconds)}",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = intervalExpanded,
                onDismissRequest = { intervalExpanded = false },
            ) {
                DesktopSettings.TEST_INTERVALS.forEach { seconds ->
                    DropdownMenuItem(
                        text = { Text(DesktopSettings.intervalLabel(seconds)) },
                        onClick = {
                            intervalExpanded = false
                            settingsStore.update { it.copy(testIntervalSeconds = seconds) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.tracerouteEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(tracerouteEnabled = it) } },
            )
            Spacer(Modifier.width(4.dp))
            Text("Include traceroute")
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        // ── Servers ───────────────────────────────────────────────────
        Text("Servers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (settings.servers.isEmpty()) {
            Text(
                "(no servers added yet)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        } else {
            settings.servers.forEach { server ->
                val sr = serverResults[server.id]
                val dotColor = when {
                    sr == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    sr.reachable -> io.slogr.desktop.ui.theme.SlogrGreen
                    else -> io.slogr.desktop.ui.theme.SlogrRed
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(dotColor, shape = CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.displayLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${server.host}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    TextButton(onClick = {
                        settingsStore.update { s ->
                            s.copy(servers = s.servers.filter { it.id != server.id })
                        }
                    }) {
                        Text("\u2715", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (showAddForm) {
            OutlinedTextField(
                value = newHost,
                onValueChange = { newHost = it },
                label = { Text("IP address or hostname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPort,
                    onValueChange = { newPort = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(100.dp),
                )
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (newHost.isNotBlank()) {
                            val entry = ServerEntry(
                                id = UUID.randomUUID().toString(),
                                host = newHost.trim(),
                                port = newPort.toIntOrNull() ?: 862,
                                label = newLabel.trim(),
                            )
                            settingsStore.update { s -> s.copy(servers = s.servers + entry) }
                            newHost = ""
                            newPort = "862"
                            newLabel = ""
                            showAddForm = false
                        }
                    },
                    enabled = newHost.isNotBlank(),
                ) {
                    Text("Add")
                }
                OutlinedButton(onClick = { showAddForm = false }) {
                    Text("Cancel")
                }
            }
        } else {
            OutlinedButton(onClick = { showAddForm = true }) {
                Text("+ Add Server")
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        // ── Application ───────────────────────────────────────────────
        Text("Application", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.autoStartEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(autoStartEnabled = it) } },
            )
            Spacer(Modifier.width(4.dp))
            Text("Start Slogr on login")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.notificationsEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(notificationsEnabled = it) } },
            )
            Spacer(Modifier.width(4.dp))
            Text("Show notifications")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Data: ${DataDirectory.resolve()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        // ── About ─────────────────────────────────────────────────────
        Text("About", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Slogr Desktop v1.1.0", style = MaterialTheme.typography.bodyMedium)
        Text(
            "slogr.io",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                isRunningDiag = true
                scope.launch {
                    diagnosticResults = DiagnosticsRunner.runAll()
                    isRunningDiag = false
                }
            },
            enabled = !isRunningDiag,
        ) {
            Text(if (isRunningDiag) "Running..." else "Run Diagnostics")
        }

        if (diagnosticResults != null) {
            Spacer(Modifier.height(8.dp))
            diagnosticResults!!.forEach { r ->
                val icon = if (r.passed) "\u2705" else "\u274C"
                Text(
                    "$icon ${r.name}: ${r.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
