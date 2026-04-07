package io.slogr.desktop.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.core.profiles.TrafficGrade
import io.slogr.desktop.ui.theme.*

@Composable
fun TrafficTypeCard(grade: TrafficGrade, modifier: Modifier = Modifier) {
    val color = when (grade.grade) {
        SlaGrade.GREEN -> SlogrGreen
        SlaGrade.YELLOW -> SlogrYellow
        SlaGrade.RED -> SlogrRed
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        // Icon
        Text(
            grade.trafficType.icon,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(4.dp))
        // Label
        Text(
            grade.trafficType.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // Grade dot
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.height(4.dp))
        // RTT + loss
        if (grade.avgRttMs >= 0) {
            Text(
                "${grade.avgRttMs.toInt()}ms",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "%.1f%% loss".format(grade.lossPct),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        } else {
            Text(
                "N/A",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}
