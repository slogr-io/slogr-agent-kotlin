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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.diagnostics.DiagnosticResult
import io.slogr.desktop.core.diagnostics.DiagnosticsRunner
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import io.slogr.desktop.core.settings.ServerEntry
import io.slogr.desktop.core.viewmodel.DesktopAgentViewModel
import io.slogr.desktop.ui.theme.*
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

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = SlogrGreen,
        focusedBorderColor = SlogrGreen,
        unfocusedBorderColor = FieldBorder,
        focusedLabelColor = SlogrGreen,
        unfocusedLabelColor = TextSecondary,
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        // ── Traffic Types ─────────────────────────────────────────────
        Text("Traffic Types", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Select up to ${DesktopSettings.MAX_ACTIVE_PROFILES}:",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        if (errorMsg != null) {
            Text(errorMsg!!, color = SlogrRed, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
        }

        ProfileManager.ALL_TRAFFIC_TYPES.forEach { tt ->
            val isActive = tt.name in activeProfiles
            val available = profileManager.isAvailable(tt.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Checkbox(
                    checked = isActive,
                    enabled = available || isActive,
                    onCheckedChange = { errorMsg = profileManager.toggleProfile(tt.name) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SlogrGreen,
                        uncheckedColor = FieldBorder,
                        checkmarkColor = SlogrBackground,
                        disabledCheckedColor = SlogrGreen.copy(alpha = 0.5f),
                        disabledUncheckedColor = FieldBorder.copy(alpha = 0.3f),
                    ),
                )
                Spacer(Modifier.width(4.dp))
                val suffix = if (!available && !isActive) " (Pro)" else ""
                Text(
                    "${tt.icon} ${tt.displayName}$suffix",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available || isActive) TextPrimary else TextDisabled,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Test interval dropdown
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
                colors = fieldColors,
            )
            ExposedDropdownMenu(
                expanded = intervalExpanded,
                onDismissRequest = { intervalExpanded = false },
            ) {
                DesktopSettings.TEST_INTERVALS.forEach { seconds ->
                    DropdownMenuItem(
                        text = { Text(DesktopSettings.intervalLabel(seconds), color = TextPrimary) },
                        onClick = {
                            intervalExpanded = false
                            settingsStore.update { it.copy(testIntervalSeconds = seconds) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.tracerouteEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(tracerouteEnabled = it) } },
                colors = CheckboxDefaults.colors(
                    checkedColor = SlogrGreen,
                    uncheckedColor = FieldBorder,
                    checkmarkColor = SlogrBackground,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Include traceroute", style = MaterialTheme.typography.bodyMedium)
                Text("Adds path trace to each test (increases test time significantly)",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = SlogrBorder)
        Spacer(Modifier.height(24.dp))

        // ── Servers ───────────────────────────────────────────────────
        Text("Servers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        if (settings.servers.isEmpty()) {
            Text(
                "No servers added yet",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        } else {
            settings.servers.forEach { server ->
                val sr = serverResults[server.id]
                val dotColor = when {
                    sr == null -> SlogrGrey.copy(alpha = 0.4f)
                    sr.reachable -> SlogrGreen
                    else -> SlogrRed
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier.size(10.dp).background(dotColor, shape = CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.displayLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${server.host}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        settingsStore.update { s -> s.copy(servers = s.servers.filter { it.id != server.id }) }
                    }) {
                        Text("x", color = SlogrRed)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (showAddForm) {
            OutlinedTextField(
                value = newHost,
                onValueChange = { newHost = it },
                label = { Text("IP address or hostname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newPort,
                    onValueChange = { newPort = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(100.dp),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = fieldColors,
                )
            }
            Spacer(Modifier.height(10.dp))
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
                            newHost = ""; newPort = "862"; newLabel = ""
                            showAddForm = false
                        }
                    },
                    enabled = newHost.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlogrGreen,
                        contentColor = SlogrBackground,
                    ),
                ) {
                    Text("Add")
                }
                OutlinedButton(
                    onClick = { showAddForm = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                ) {
                    Text("Cancel")
                }
            }
        } else {
            OutlinedButton(
                onClick = { showAddForm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SlogrGreen),
                border = androidx.compose.foundation.BorderStroke(1.dp, SlogrGreen),
            ) {
                Text("+ Add Server")
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = SlogrBorder)
        Spacer(Modifier.height(24.dp))

        // ── Application ───────────────────────────────────────────────
        Text("Application", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Checkbox(
                checked = settings.autoStartEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(autoStartEnabled = it) } },
                colors = CheckboxDefaults.colors(
                    checkedColor = SlogrGreen, uncheckedColor = FieldBorder, checkmarkColor = SlogrBackground,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text("Start Slogr on login", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Checkbox(
                checked = settings.notificationsEnabled,
                onCheckedChange = { settingsStore.update { s -> s.copy(notificationsEnabled = it) } },
                colors = CheckboxDefaults.colors(
                    checkedColor = SlogrGreen, uncheckedColor = FieldBorder, checkmarkColor = SlogrBackground,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text("Show notifications", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Data: ${DataDirectory.resolve()}",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = SlogrBorder)
        Spacer(Modifier.height(24.dp))

        // ── About ─────────────────────────────────────────────────────
        Text("About", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Slogr Desktop v1.1.0", style = MaterialTheme.typography.bodyMedium)
        Text("slogr.io", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                isRunningDiag = true
                scope.launch {
                    diagnosticResults = DiagnosticsRunner.runAll()
                    isRunningDiag = false
                }
            },
            enabled = !isRunningDiag,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SlogrGreen),
            border = androidx.compose.foundation.BorderStroke(1.dp, SlogrGreen),
        ) {
            Text(if (isRunningDiag) "Running..." else "Run Diagnostics")
        }

        if (diagnosticResults != null) {
            Spacer(Modifier.height(10.dp))
            diagnosticResults!!.forEach { r ->
                val prefix = if (r.passed) "[OK]" else "[FAIL]"
                Text(
                    "$prefix ${r.name}: ${r.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.passed) SlogrGreen else SlogrRed,
                )
                Spacer(Modifier.height(2.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
