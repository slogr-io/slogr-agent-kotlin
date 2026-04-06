package io.slogr.desktop.ui.window

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import io.slogr.desktop.core.history.HistoryEntry
import io.slogr.desktop.ui.theme.SlogrGreen
import io.slogr.desktop.ui.theme.SlogrGrey
import io.slogr.desktop.ui.theme.SlogrRed
import io.slogr.desktop.ui.theme.SlogrYellow
import io.slogr.agent.contracts.SlaGrade

/**
 * Sparkline-style bar chart showing RTT over the last 24h.
 * Each bar represents one measurement entry, colored by grade.
 */
@Composable
fun HistoryChart(entries: List<HistoryEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text(
            "No history yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
        )
        return
    }

    // Show most recent entries (oldest → newest, left to right)
    val sorted = entries.sortedBy { it.measuredAt }
    val maxRtt = sorted.maxOf { it.avgRttMs }.coerceAtLeast(1f)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            val barCount = sorted.size
            val barWidth = (size.width / barCount.coerceAtLeast(1)).coerceAtMost(12f)
            val gap = 1f
            val totalWidth = barCount * (barWidth + gap)
            val startX = (size.width - totalWidth).coerceAtLeast(0f)

            sorted.forEachIndexed { i, entry ->
                val barHeight = (entry.avgRttMs / maxRtt * size.height).coerceIn(2f, size.height)
                val color = when (entry.grade) {
                    SlaGrade.GREEN -> SlogrGreen
                    SlaGrade.YELLOW -> SlogrYellow
                    SlaGrade.RED -> SlogrRed
                }
                drawRect(
                    color = color,
                    topLeft = Offset(
                        x = startX + i * (barWidth + gap),
                        y = size.height - barHeight,
                    ),
                    size = Size(barWidth, barHeight),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Axis labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val oldest = sorted.firstOrNull()?.measuredAt
            val newest = sorted.lastOrNull()?.measuredAt
            Text(
                oldest?.let { formatTime(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
            Text(
                newest?.let { formatTime(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }
    }
}

private fun formatTime(instant: kotlinx.datetime.Instant): String {
    // Simple HH:MM format from ISO string
    val str = instant.toString()
    return if (str.length >= 16) str.substring(11, 16) else str
}
