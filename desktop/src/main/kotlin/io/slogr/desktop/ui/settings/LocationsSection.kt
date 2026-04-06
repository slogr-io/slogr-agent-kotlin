package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LocationsSection() {
    Text("Locations", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    Text(
        "Slogr Reflectors",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "Reflector discovery will be configured in Phase 2.\n" +
            "Reflectors will be auto-selected based on proximity.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
}
