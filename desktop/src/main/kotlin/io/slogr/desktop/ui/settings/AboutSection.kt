package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.diagnostics.DiagnosticResult
import io.slogr.desktop.core.diagnostics.DiagnosticsRunner
import kotlinx.coroutines.launch

@Composable
fun AboutSection() {
    var diagnosticResults by remember { mutableStateOf<List<DiagnosticResult>?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("About", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    Text("Slogr Desktop v1.1.0", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))

    listOf(
        "\u00a9 2026 Slogr",
        "",
        "Website: slogr.io",
        "Support: support@slogr.io",
    ).forEach { line ->
        if (line.isEmpty()) {
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))

    // Diagnostics
    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            isRunning = true
            scope.launch {
                diagnosticResults = DiagnosticsRunner.runAll()
                isRunning = false
            }
        },
        enabled = !isRunning,
    ) {
        Text(if (isRunning) "Running..." else "Run Diagnostics")
    }

    if (diagnosticResults != null) {
        Spacer(Modifier.height(12.dp))
        diagnosticResults!!.forEach { result ->
            val icon = if (result.passed) "\u2705" else "\u274C"
            Text(
                "$icon ${result.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.passed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            Text(
                "   ${result.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
