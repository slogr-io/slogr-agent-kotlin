package io.slogr.desktop.ui.window

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.core.profiles.TrafficGrade
import io.slogr.desktop.ui.theme.*
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
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        if (!hasServers) {
            Spacer(Modifier.height(48.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No servers configured",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Add a TWAMP server to start\nmonitoring your connection quality.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onGoToSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlogrGreen,
                        contentColor = SlogrBackground,
                    ),
                ) {
                    Text("Go to Settings")
                }
            }
            return
        }

        // Traffic type cards
        if (trafficGrades.isEmpty() && isMeasuring) {
            Text(
                "Measuring...",
                style = MaterialTheme.typography.titleMedium,
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

        Spacer(Modifier.height(24.dp))

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
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onRunTestNow,
                enabled = !isMeasuring && hasServers,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlogrGreen,
                    contentColor = SlogrBackground,
                    disabledContainerColor = SlogrGreen.copy(alpha = 0.3f),
                    disabledContentColor = TextDisabled,
                ),
            ) {
                Text(if (isMeasuring) "Testing..." else "Run Test Now")
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = SlogrBorder)
        Spacer(Modifier.height(16.dp))

        Text("Recent History (24h)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        HistoryChart(entries = recentHistory, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}
