package io.slogr.desktop.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopSettingsStore(private val dataDir: Path) {

    private val settingsFile = dataDir.resolve("settings.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(DesktopSettings())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    fun load() {
        if (settingsFile.exists()) {
            try {
                _settings.value = json.decodeFromString(settingsFile.readText())
            } catch (_: Exception) {
                // Corrupted file — use defaults, will be overwritten on next save
            }
        }
    }

    fun save(settings: DesktopSettings) {
        _settings.value = settings
        dataDir.createDirectories()
        settingsFile.writeText(json.encodeToString(DesktopSettings.serializer(), settings))
    }

    fun update(block: (DesktopSettings) -> DesktopSettings) {
        save(block(_settings.value))
    }
}
