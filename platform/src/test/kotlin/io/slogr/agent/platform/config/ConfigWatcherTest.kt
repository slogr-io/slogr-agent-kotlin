package io.slogr.agent.platform.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConfigWatcherTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `onReload called when config file is modified`() {
        val configFile = tempDir.resolve("agent.yaml")
        Files.writeString(configFile, "SLOGR_API_KEY: sk_free_initial\n")

        val latch   = CountDownLatch(1)
        val watcher = ConfigWatcher(configFile) { latch.countDown() }
        watcher.start()

        // Allow watcher thread to initialise
        Thread.sleep(200)

        // Modify the config file
        Files.writeString(configFile, "SLOGR_API_KEY: sk_live_newkey\n")

        val triggered = latch.await(3, TimeUnit.SECONDS)
        assertTrue(triggered, "onReload should have been called within 3 seconds")
        watcher.close()
    }

    @Test
    fun `start is a no-op when config directory does not exist`() {
        val nonExistentConfig = tempDir.resolve("nonexistent/subdir/agent.yaml")
        val watcher = ConfigWatcher(nonExistentConfig) {
            fail("onReload must not be called for non-existent directory")
        }
        // Should not throw
        assertDoesNotThrow { watcher.start() }
        watcher.close()
    }
}
