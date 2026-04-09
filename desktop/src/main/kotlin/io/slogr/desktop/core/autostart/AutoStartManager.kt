package io.slogr.desktop.core.autostart

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Manages auto-start on login for Windows (registry) and macOS (LaunchAgent).
 */
object AutoStartManager {

    private val log = LoggerFactory.getLogger(AutoStartManager::class.java)

    private const val APP_NAME = "Slogr"
    private const val REGISTRY_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val LAUNCH_AGENT_LABEL = "io.slogr.desktop"

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    fun enable() {
        try {
            if (isWindows) enableWindows()
            else if (isMac) enableMac()
        } catch (e: Exception) {
            log.warn("Failed to enable auto-start: {}", e.message)
        }
    }

    fun disable() {
        try {
            if (isWindows) disableWindows()
            else if (isMac) disableMac()
        } catch (e: Exception) {
            log.warn("Failed to disable auto-start: {}", e.message)
        }
    }

    fun isEnabled(): Boolean {
        return try {
            if (isWindows) isEnabledWindows()
            else if (isMac) isEnabledMac()
            else false
        } catch (_: Exception) {
            false
        }
    }

    // --- Windows: Registry ---

    private fun enableWindows() {
        val appPath = resolveAppPath()
        ProcessBuilder(
            "reg", "add", REGISTRY_KEY,
            "/v", APP_NAME,
            "/t", "REG_SZ",
            "/d", "\"$appPath\" --background",
            "/f",
        ).start().waitFor()
        log.info("Auto-start enabled (Windows registry)")
    }

    private fun disableWindows() {
        ProcessBuilder(
            "reg", "delete", REGISTRY_KEY,
            "/v", APP_NAME,
            "/f",
        ).start().waitFor()
        log.info("Auto-start disabled (Windows registry)")
    }

    private fun isEnabledWindows(): Boolean {
        val proc = ProcessBuilder(
            "reg", "query", REGISTRY_KEY,
            "/v", APP_NAME,
        ).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return output.contains(APP_NAME)
    }

    // --- macOS: LaunchAgent ---

    private val launchAgentPath: Path
        get() = Path.of(
            System.getProperty("user.home"),
            "Library", "LaunchAgents", "$LAUNCH_AGENT_LABEL.plist",
        )

    private fun enableMac() {
        val appPath = resolveAppPath()
        val plist = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>$LAUNCH_AGENT_LABEL</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>$appPath</string>
            |        <string>--background</string>
            |    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |</dict>
            |</plist>
        """.trimMargin()

        launchAgentPath.parent.createDirectories()
        launchAgentPath.writeText(plist)
        log.info("Auto-start enabled (macOS LaunchAgent)")
    }

    private fun disableMac() {
        launchAgentPath.deleteIfExists()
        log.info("Auto-start disabled (macOS LaunchAgent)")
    }

    private fun isEnabledMac(): Boolean = launchAgentPath.exists()

    private fun resolveAppPath(): String {
        // Prefer the launcher script path; fall back to java executable
        val javaHome = System.getProperty("java.home") ?: return "slogr"
        return Path.of(javaHome, "bin", "java").toString()
    }
}
