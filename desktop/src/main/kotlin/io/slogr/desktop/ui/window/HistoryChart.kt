package io.slogr.desktop.ui.window

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.history.LocalHistoryStore
import io.slogr.desktop.core.profiles.ProfileManager
import io.slogr.desktop.ui.theme.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/**
 * History section: dropdown to select traffic type, time-positioned colored dots.
 * Dots are spaced proportionally on a fixed 24h timeline.
 * If the type was never tested, falls back to baseline results.
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

    val now = Clock.System.now()
    val timelineStart = now - 24.hours

    // Load history when type changes
    LaunchedEffect(selectedType) {
        if (historyStore == null) return@LaunchedEffect
        val direct = historyStore.getResultsForProfile(selectedType, limit = 500)
        if (direct.isNotEmpty()) {
            entries = direct.filter { it.measuredAt >= timelineStart }.sortedBy { it.measuredAt }
        } else {
            val tt = ProfileManager.ALL_TRAFFIC_TYPES.find { it.name == selectedType }
            if (tt != null) {
                val baseline = historyStore.getBaselineAsProfile(profileManager.toSlaProfile(tt), limit = 500)
                entries = baseline.filter { it.measuredAt >= timelineStart }.sortedBy { it.measuredAt }
            } else {
                entries = emptyList()
            }
        }
    }

    Column(modifier = modifier) {
        // Header row: title + dropdown
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Recent History (24h)", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { dropdownExpanded = it }) {
                val displayName = ProfileManager.ALL_TRAFFIC_TYPES.find { it.name == selectedType }?.displayName ?: selectedType
                OutlinedTextField(
                    value = displayName, onValueChange = {}, readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).width(180.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SlogrGreen, unfocusedBorderColor = FieldBorder,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    ),
                )
                ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                    ProfileManager.ALL_TRAFFIC_TYPES.forEach { tt ->
                        DropdownMenuItem(
                            text = { Text("${tt.icon} ${tt.displayName}", color = TextPrimary) },
                            onClick = { selectedType = tt.name; dropdownExpanded = false },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Text("No history for this traffic type", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        } else {
            // Time-positioned dots on a 24h fixed timeline
            val timelineMs = 24.hours.inWholeMilliseconds.toFloat()
            val startMs = timelineStart.toEpochMilliseconds().toFloat()

            Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                val dotRadius = 5.dp.toPx()

                // Draw timeline background line
                drawLine(
                    color = SlogrBorder,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 1.dp.toPx(),
                )

                entries.forEach { entry ->
                    val entryMs = entry.measuredAt.toEpochMilliseconds().toFloat()
                    val fraction = ((entryMs - startMs) / timelineMs).coerceIn(0f, 1f)
                    val x = fraction * size.width

                    val color = when (entry.grade) {
                        SlaGrade.GREEN -> SlogrGreen
                        SlaGrade.YELLOW -> SlogrYellow
                        SlaGrade.RED -> SlogrRed
                    }
                    drawCircle(color = color, radius = dotRadius, center = Offset(x, size.height / 2))
                }
            }

            // Time axis labels: 6 evenly spaced
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..5) {
                    val labelTime = timelineStart + (24.hours * i / 5)
                    Text(formatTime(labelTime), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }
    }
}

private fun formatTime(instant: Instant): String {
    val str = instant.toString()
    return if (str.length >= 16) str.substring(11, 16) else str
}

// Backward compat stub
@Composable
fun HistoryChart(entries: List<HistoryEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) Text("No history yet", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
}
