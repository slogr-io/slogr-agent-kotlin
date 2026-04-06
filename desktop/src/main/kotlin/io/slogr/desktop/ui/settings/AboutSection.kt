package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AboutSection() {
    Text("About", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    Text("Slogr Desktop v1.1.0", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))

    val info = listOf(
        "\u00a9 2026 Slogr",
        "",
        "Website: slogr.io",
        "Support: support@slogr.io",
    )
    info.forEach { line ->
        if (line.isEmpty()) {
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    Text(
        "Update checking and diagnostics will be available in a future phase.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    )
}
