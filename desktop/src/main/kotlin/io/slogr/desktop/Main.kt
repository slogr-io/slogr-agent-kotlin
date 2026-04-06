package io.slogr.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.slogr.desktop.ui.theme.SlogrTheme
import io.slogr.desktop.ui.tray.TrayIconGenerator

fun main() = application {
    var isWindowVisible by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(480.dp, 640.dp))
    val trayIcon = remember { TrayIconGenerator.greyIcon() }

    Tray(
        icon = trayIcon,
        tooltip = "Slogr",
        onAction = { isWindowVisible = true },
        menu = {
            Item("Open Window", onClick = { isWindowVisible = true })
            Separator()
            Item("Quit", onClick = ::exitApplication)
        },
    )

    Window(
        onCloseRequest = { isWindowVisible = false },
        visible = isWindowVisible,
        title = "Slogr",
        state = windowState,
    ) {
        window.minimumSize = java.awt.Dimension(400, 500)

        SlogrTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Slogr Desktop — scaffold ready",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
