package io.slogr.desktop.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.slogr.agent.platform.config.AgentState

@Composable
fun AccountSection(
    agentState: AgentState,
    onSetApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }

    Text("Account", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    // State badge
    val (stateLabel, stateColor) = when (agentState) {
        AgentState.ANONYMOUS -> "Anonymous" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        AgentState.REGISTERED -> "Registered (Free)" to MaterialTheme.colorScheme.primary
        AgentState.CONNECTED -> "Connected (Pro)" to MaterialTheme.colorScheme.primary
    }
    Text("Status: $stateLabel", color = stateColor)

    Spacer(Modifier.height(16.dp))

    when (agentState) {
        AgentState.ANONYMOUS -> {
            Text(
                "Not signed in. Enter an API key to connect.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign-in via OAuth will be available in a future update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
        AgentState.REGISTERED -> {
            Text("Plan: Free")
            Spacer(Modifier.height(8.dp))
            Text(
                "Upgrade to Pro for full SaaS dashboard access.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        AgentState.CONNECTED -> {
            Text("Plan: Pro")
        }
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(16.dp))

    // API key entry
    Text("Enter API Key", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text("API Key") },
        placeholder = { Text("sk_free_... or sk_live_...") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                if (keyInput.isNotBlank()) {
                    onSetApiKey(keyInput.trim())
                    keyInput = ""
                }
            },
            enabled = keyInput.isNotBlank(),
        ) {
            Text("Apply Key")
        }

        if (agentState != AgentState.ANONYMOUS) {
            OutlinedButton(onClick = onClearApiKey) {
                Text("Sign Out")
            }
        }
    }
}
