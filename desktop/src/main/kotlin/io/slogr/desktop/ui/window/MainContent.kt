package io.slogr.desktop.ui.window

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.viewmodel.ReflectorResult
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock

@Composable
fun MainContent(
    overallGrade: SlaGrade?,
    isMeasuring: Boolean,
    results: Map<String, ReflectorResult>,
    lastTestTime: Instant?,
    onRunTestNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // Grade badge
        GradeBadge(grade = overallGrade, isMeasuring = isMeasuring)

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        // Locations header
        Text(
            "Locations",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))

        if (results.isEmpty()) {
            Text(
                if (isMeasuring) "Running first measurement..."
                else "No measurements yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        } else {
            results.values
                .sortedBy { it.regionName }
                .forEach { result ->
                    LocationCard(result = result)
                    Spacer(Modifier.height(6.dp))
                }
        }

        Spacer(Modifier.height(12.dp))

        // Last test time + Run Now button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val timeLabel = if (lastTestTime != null) {
                val elapsed = Clock.System.now() - lastTestTime
                val mins = elapsed.inWholeMinutes
                when {
                    mins < 1 -> "Last test: just now"
                    mins == 1L -> "Last test: 1 minute ago"
                    else -> "Last test: $mins minutes ago"
                }
            } else {
                "No tests run yet"
            }
            Text(
                timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = onRunTestNow,
                enabled = !isMeasuring,
            ) {
                Text(if (isMeasuring) "Testing..." else "\u25B6 Run Test Now")
            }
        }

        Spacer(Modifier.height(16.dp))

        // History placeholder (Phase 4)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(
            "Recent History (24h)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "History chart coming in Phase 4",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )

        Spacer(Modifier.height(16.dp))
    }
}
