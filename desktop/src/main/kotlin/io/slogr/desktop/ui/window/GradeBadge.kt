package io.slogr.desktop.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.slogr.agent.contracts.SlaGrade
import io.slogr.desktop.ui.theme.SlogrGreen
import io.slogr.desktop.ui.theme.SlogrGrey
import io.slogr.desktop.ui.theme.SlogrRed
import io.slogr.desktop.ui.theme.SlogrYellow

@Composable
fun GradeBadge(grade: SlaGrade?, isMeasuring: Boolean) {
    val (color, label, subtitle) = when {
        isMeasuring && grade == null -> Triple(SlogrGrey, "Measuring...", null)
        grade == null -> Triple(SlogrGrey, "No Data", "Waiting for first measurement")
        grade == SlaGrade.GREEN -> Triple(SlogrGreen, "GREEN", "Connection quality is good")
        grade == SlaGrade.YELLOW -> Triple(
            SlogrYellow, "YELLOW", "Elevated latency or jitter detected",
        )
        else -> Triple(SlogrRed, "RED", "Significant degradation detected")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    ) {
        Text(
            "Connection Quality",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(horizontal = 32.dp, vertical = 12.dp),
        ) {
            Text(
                label,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }

        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

fun gradeColor(grade: SlaGrade?): Color = when (grade) {
    SlaGrade.GREEN -> SlogrGreen
    SlaGrade.YELLOW -> SlogrYellow
    SlaGrade.RED -> SlogrRed
    null -> SlogrGrey
}
