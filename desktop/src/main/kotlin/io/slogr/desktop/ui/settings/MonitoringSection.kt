package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.core.profiles.ProfileTier
import io.slogr.desktop.core.settings.DesktopSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringSection(
    settings: DesktopSettings,
    agentState: AgentState,
    profileManager: ProfileManager,
    onUpdateSettings: (DesktopSettings) -> Unit,
) {
    val activeProfileName by profileManager.activeProfileName.collectAsState()

    Text("Monitoring", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    // Profile selector
    Text("Profile", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    var profileExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = profileExpanded,
        onExpandedChange = { profileExpanded = it },
    ) {
        val current = ProfileManager.DESKTOP_PROFILES.find { it.name == activeProfileName }
        OutlinedTextField(
            value = current?.displayName ?: activeProfileName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )

        ExposedDropdownMenu(
            expanded = profileExpanded,
            onDismissRequest = { profileExpanded = false },
        ) {
            ProfileManager.DESKTOP_PROFILES.forEach { profile ->
                val available = profileManager.isProfileAvailable(profile.name)
                val suffix = when {
                    available -> ""
                    profile.tier == ProfileTier.PAID_ONLY -> " (Pro)"
                    profile.tier == ProfileTier.FREE_PICK && settings.secondFreeProfile == null ->
                        " (select as free pick)"
                    profile.tier == ProfileTier.FREE_PICK -> " (Pro)"
                    else -> ""
                }

                DropdownMenuItem(
                    text = {
                        Text(
                            "${profile.displayName}$suffix",
                            color = if (available) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    },
                    onClick = {
                        profileExpanded = false
                        if (available) {
                            profileManager.selectProfile(profile.name)
                        } else if (profile.tier == ProfileTier.FREE_PICK && settings.secondFreeProfile == null) {
                            // First free pick — let user choose
                            profileManager.setSecondFreeProfile(profile.name)
                            profileManager.selectProfile(profile.name)
                        }
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    // Test interval
    Text("Test Interval", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    var intervalExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = intervalExpanded,
        onExpandedChange = { intervalExpanded = it },
    ) {
        OutlinedTextField(
            value = DesktopSettings.intervalLabel(settings.testIntervalSeconds),
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
                        onUpdateSettings(settings.copy(testIntervalSeconds = seconds))
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    // Traceroute toggle
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = settings.tracerouteEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(tracerouteEnabled = it)) },
        )
        Spacer(Modifier.width(8.dp))
        Text("Include traceroute with tests")
    }
}
