package io.slogr.desktop.ui.window

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.profiles.TrafficGrade
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

@Composable
fun DashboardView(
    trafficGrades: List<TrafficGrade>,
    isMeasuring: Boolean,
    lastTestTime: Instant?,
    recentHistory: List<HistoryEntry>,
    hasServers: Boolean,
    onRunTestNow: () -> Unit,
    onGoToSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (!hasServers) {
            // Empty state
            Spacer(Modifier.height(48.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No servers configured",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Add a TWAMP server to start\nmonitoring your connection quality.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onGoToSettings) {
                    Text("Go to Settings")
                }
            }
            return
        }

        // Traffic type cards (3 across or wrapping)
        if (trafficGrades.isEmpty() && isMeasuring) {
            Text(
                "Measuring...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else if (trafficGrades.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                trafficGrades.forEach { grade ->
                    TrafficTypeCard(
                        grade = grade,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Last test + Run Test Now
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val timeLabel = if (lastTestTime != null) {
                val mins = (Clock.System.now() - lastTestTime).inWholeMinutes
                when {
                    mins < 1 -> "Last test: just now"
                    mins == 1L -> "Last test: 1 min ago"
                    else -> "Last test: $mins min ago"
                }
            } else "No tests run yet"
            Text(
                timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onRunTestNow,
                enabled = !isMeasuring && hasServers,
            ) {
                Text(if (isMeasuring) "Testing..." else "\u25B6 Run Test Now")
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        Text("Recent History (24h)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        HistoryChart(entries = recentHistory, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}
