package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.reflectors.Reflector
import io.slogr.desktop.core.reflectors.ReflectorDiscoveryClient
import io.slogr.desktop.core.settings.DesktopSettings
import io.slogr.desktop.core.settings.DesktopSettingsStore
import kotlinx.coroutines.launch

@Composable
fun LocationsSection(
    discoveryClient: ReflectorDiscoveryClient,
    settingsStore: DesktopSettingsStore,
    agentState: AgentState,
) {
    val reflectors by discoveryClient.reflectors.collectAsState()
    val isLoading by discoveryClient.isLoading.collectAsState()
    val error by discoveryClient.error.collectAsState()
    val settings by settingsStore.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val accessible = discoveryClient.filterByTier(reflectors)
    val locked = reflectors.filter { it !in accessible }

    Text("Locations", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    Text("Slogr Reflectors", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    if (error != null && reflectors.isEmpty()) {
        Text(
            error!!,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (reflectors.isEmpty() && !isLoading) {
        Text(
            "No reflectors discovered yet.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }

    // Accessible reflectors
    accessible.forEach { reflector ->
        ReflectorRow(
            reflector = reflector,
            isSelected = reflector.id in settings.selectedReflectorIds,
            isLocked = false,
            onToggle = { selected ->
                val ids = settings.selectedReflectorIds.toMutableList()
                if (selected) ids.add(reflector.id) else ids.remove(reflector.id)
                settingsStore.update { it.copy(selectedReflectorIds = ids) }
            },
        )
    }

    // Locked reflectors
    locked.forEach { reflector ->
        ReflectorRow(
            reflector = reflector,
            isSelected = false,
            isLocked = true,
            onToggle = {},
        )
    }

    Spacer(Modifier.height(16.dp))

    // Refresh button
    OutlinedButton(
        onClick = { scope.launch { discoveryClient.refresh() } },
        enabled = !isLoading,
    ) {
        Text(if (isLoading) "Refreshing..." else "Refresh reflector list")
    }
}

@Composable
private fun ReflectorRow(
    reflector: Reflector,
    isSelected: Boolean,
    isLocked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = if (isLocked) null else onToggle,
            enabled = !isLocked,
        )
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            val suffix = if (isLocked) " (Pro)" else ""
            Text(
                "${reflector.displayName}$suffix",
                color = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${reflector.host}:${reflector.port} — ${reflector.tier}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}
