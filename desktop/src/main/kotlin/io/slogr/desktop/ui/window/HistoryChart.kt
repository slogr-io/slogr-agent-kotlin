package io.slogr.desktop.ui.window

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.ui.theme.*
import kotlinx.coroutines.launch

/**
 * History section: dropdown to select a traffic type, colored dots showing grade over time.
 * If the selected type has no direct history, falls back to baseline results re-evaluated
 * against that type's SLA thresholds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySection(
    historyStore: LocalHistoryStore?,
    profileManager: ProfileManager,
    modifier: Modifier = Modifier,
) {
    var selectedType by remember { mutableStateOf(ProfileManager.ALL_TRAFFIC_TYPES.first().name) }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Load history when type changes
    LaunchedEffect(selectedType) {
        if (historyStore == null) return@LaunchedEffect
        // Try direct history first
        val direct = historyStore.getResultsForProfile(selectedType, limit = 200)
        if (direct.isNotEmpty()) {
            entries = direct.reversed() // oldest first for left-to-right display
        } else {
            // Fall back to baseline re-evaluated against this type's SLA
            val tt = ProfileManager.ALL_TRAFFIC_TYPES.find { it.name == selectedType }
            if (tt != null) {
                val baseline = historyStore.getBaselineAsProfile(profileManager.toSlaProfile(tt), limit = 200)
                entries = baseline.reversed()
            } else {
                entries = emptyList()
            }
        }
    }

    Column(modifier = modifier) {
        // Dropdown to select traffic type
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Recent History (24h)", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = it }) {
                val displayName = ProfileManager.ALL_TRAFFIC_TYPES.find { it.name == selectedType }?.displayName ?: selectedType
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).width(180.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SlogrGreen, unfocusedBorderColor = FieldBorder,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedContainerColor = androidx.compose.ui.graphics.Color.White,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
                    ),
                )
                ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                    ProfileManager.ALL_TRAFFIC_TYPES.forEach { tt ->
                        DropdownMenuItem(
                            text = { Text("${tt.icon} ${tt.displayName}", color = TextPrimary) },
                            onClick = {
                                selectedType = tt.name
                                dropdownExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Text("No history for this traffic type", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        } else {
            // Colored dots — one per measurement
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                val dotSize = 8.dp.toPx()
                val gap = 3.dp.toPx()
                val maxDots = ((size.width + gap) / (dotSize + gap)).toInt()
                val displayEntries = entries.takeLast(maxDots)

                displayEntries.forEachIndexed { i, entry ->
                    val color = when (entry.grade) {
                        SlaGrade.GREEN -> SlogrGreen
                        SlaGrade.YELLOW -> SlogrYellow
                        SlaGrade.RED -> SlogrRed
                    }
                    drawCircle(
                        color = color,
                        radius = dotSize / 2,
                        center = Offset(
                            x = i * (dotSize + gap) + dotSize / 2,
                            y = size.height / 2,
                        ),
                    )
                }
            }

            // Time labels
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val oldest = entries.firstOrNull()?.measuredAt
                val newest = entries.lastOrNull()?.measuredAt
                Text(oldest?.let { formatTime(it) } ?: "", style = MaterialTheme.typography.labelSmall)
                Text(newest?.let { formatTime(it) } ?: "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatTime(instant: kotlinx.datetime.Instant): String {
    val str = instant.toString()
    return if (str.length >= 16) str.substring(11, 16) else str
}

// Keep old HistoryChart for backward compat (unused but tests may reference)
@Composable
fun HistoryChart(entries: List<HistoryEntry>, modifier: Modifier = Modifier) {
    // Deprecated — use HistorySection instead
    if (entries.isEmpty()) {
        Text("No history yet", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
