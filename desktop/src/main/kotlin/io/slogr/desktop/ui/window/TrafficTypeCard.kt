package io.slogr.desktop.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val gradeColor = when (grade.grade) {
        SlaGrade.GREEN -> SlogrGreen
        SlaGrade.YELLOW -> SlogrYellow
        SlaGrade.RED -> SlogrRed
        null -> SlogrGrey  // testing/pending
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(SlogrCardBg, RoundedCornerShape(10.dp))
            .border(1.dp, SlogrBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(grade.trafficType.icon, fontSize = 26.sp)
        Spacer(Modifier.height(4.dp))
        Text(grade.trafficType.displayName, fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(gradeColor))
        Spacer(Modifier.height(4.dp))
        if (grade.grade == null) {
            Text("Testing...", fontSize = 12.sp, color = TextSecondary)
        } else if (grade.avgRttMs >= 0) {
            Text("${grade.avgRttMs.toInt()}ms", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("%.1f%% loss".format(grade.lossPct), fontSize = 11.sp, color = TextSecondary)
        } else {
            Text("Failed", fontSize = 14.sp, color = SlogrRed)
        }
    }
}
