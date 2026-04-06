package io.slogr.desktop.core

import java.nio.file.Path

object DataDirectory {

    fun resolve(): Path {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                    ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString()
                Path.of(appData, "Slogr")
            }
            os.contains("mac") -> Path.of(
                System.getProperty("user.home"),
                "Library", "Application Support", "Slogr",
            )
            else -> Path.of(System.getProperty("user.home"), ".slogr")
        }
    }
}
