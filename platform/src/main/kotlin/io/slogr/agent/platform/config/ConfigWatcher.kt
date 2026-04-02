package io.slogr.agent.platform.config

import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import kotlin.concurrent.thread

/**
 * Watches [configPath] for changes and invokes [onReload] when the file is modified.
 *
 * Used for hot key reload: when `/etc/slogr/agent.yaml` changes (e.g. new `SLOGR_API_KEY`),
 * the agent re-evaluates state and re-registers if necessary without a restart.
 *
 * Runs a daemon thread so the JVM can exit cleanly.
 */
class ConfigWatcher(
    private val configPath: Path,
    private val onReload: () -> Unit
) {
    private val log          = LoggerFactory.getLogger(ConfigWatcher::class.java)
    private val watchService = FileSystems.getDefault().newWatchService()

    /**
     * Starts the background watcher thread.
     * The parent directory of [configPath] must exist.
     */
    fun start() {
        if (!configPath.parent.toFile().exists()) {
            log.debug("Config directory does not exist; hot-reload disabled: $configPath")
            return
        }
        configPath.parent.register(watchService, ENTRY_MODIFY)
        thread(name = "config-watcher", isDaemon = true) {
            try {
                while (true) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        if (event.context().toString() == configPath.fileName.toString()) {
                            log.info("Config file changed. Reloading credentials...")
                            runCatching(onReload).onFailure {
                                log.warn("Reload callback failed: ${it.message}")
                            }
                        }
                    }
                    if (!key.reset()) break
                }
            } catch (_: InterruptedException) {
                // Daemon thread interrupted on JVM shutdown — normal exit
            }
        }
    }

    /** Closes the underlying [WatchService]. Call on shutdown if needed. */
    fun close() { runCatching { watchService.close() } }
}
