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
        null -> SlogrGrey
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(SlogrCardBg, RoundedCornerShape(12.dp))
            .border(1.dp, SlogrBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        // Large icon
        Text(grade.trafficType.icon, fontSize = 38.sp)
        Spacer(Modifier.height(6.dp))
        // Type name
        Text(grade.trafficType.displayName, fontSize = 13.sp, color = TextSecondary,
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        // Grade dot
        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(gradeColor))
        Spacer(Modifier.height(8.dp))

        if (grade.grade == null) {
            Text("Testing...", fontSize = 12.sp, color = TextSecondary)
        } else if (grade.avgRttMs >= 0) {
            // Total RTT
            Text("${grade.avgRttMs.toInt()}ms", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            // Uplink / Downlink split
            if (grade.fwdRttMs > 0 || grade.revRttMs > 0) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\u2191${grade.fwdRttMs.toInt()}ms", fontSize = 11.sp, color = TextSecondary)
                    Text("\u2193${grade.revRttMs.toInt()}ms", fontSize = 11.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("%.1f%% loss".format(grade.lossPct), fontSize = 11.sp, color = TextSecondary)
        } else {
            Text("Failed", fontSize = 14.sp, color = SlogrRed)
        }
    }
}
