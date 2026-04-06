package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.DataDirectory
import io.slogr.desktop.core.settings.DesktopSettings

@Composable
fun ApplicationSection(
    settings: DesktopSettings,
    onUpdateSettings: (DesktopSettings) -> Unit,
) {
    Text("Application", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    // Auto-start toggle
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = settings.autoStartEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(autoStartEnabled = it)) },
        )
        Spacer(Modifier.width(8.dp))
        Text("Start Slogr on login")
    }

    Spacer(Modifier.height(4.dp))

    // Notifications toggle
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = settings.notificationsEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(notificationsEnabled = it)) },
        )
        Spacer(Modifier.width(8.dp))
        Text("Show desktop notifications")
    }

    Spacer(Modifier.height(4.dp))

    // Minimize to tray toggle
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = settings.minimizeToTrayOnClose,
            onCheckedChange = { onUpdateSettings(settings.copy(minimizeToTrayOnClose = it)) },
        )
        Spacer(Modifier.width(8.dp))
        Text("Minimize to tray on close")
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))

    // Data directory
    Text("Data Directory", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        DataDirectory.resolve().toString(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}
