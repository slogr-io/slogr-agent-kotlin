package io.slogr.agent.platform.identity

import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

/**
 * Provides a stable machine fingerprint that persists across container restarts,
 * MAC address changes (R2-FP-04), and VM clones (R2-FP-03).
 *
 * **On first call with no existing file**: generates `UUID|mac|hostname`, writes to disk.
 * **On subsequent calls**: reads from the persisted file.
 *
 * The UUID component guarantees divergence between cloned VMs that share the same
 * MAC address and hostname (R2-FP-03). A containerised agent on a mounted volume
 * keeps the same fingerprint even after restart with a new MAC (R2-FP-04).
 *
 * Storage locations tried in order:
 * 1. `dataDir/.agent_fingerprint`   — passed by caller (tests / container deploy)
 * 2. `/var/lib/slogr/.agent_fingerprint` — production Linux path
 * 3. `${user.home}/.slogr/.agent_fingerprint` — dev / Windows fallback
 */
object PersistentFingerprint {

    private val defaultPaths = listOf(
        "/var/lib/slogr/.agent_fingerprint",
        "${System.getProperty("user.home")}/.slogr/.agent_fingerprint"
    )

    /**
     * Path list override used only in unit tests to prevent interference from
     * production paths that may already exist on the build machine.
     *
     * Set to a single-element list pointing at the test's `@TempDir` before each
     * test, and reset to `null` in `@AfterEach`.
     */
    @JvmField
    internal var testPaths: List<String>? = null

    /**
     * Returns the persistent fingerprint for this machine.
     *
     * @param dataDir Optional directory override; when provided it is tried first.
     */
    fun get(dataDir: String? = null): String {
        val paths = testPaths ?: buildPaths(dataDir)

        // Try to read an existing fingerprint
        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                if (content.isNotEmpty()) return content
            }
        }

        // No file found — generate and persist
        val fingerprint = generate()
        for (path in paths) {
            if (tryWrite(path, fingerprint)) break
        }
        return fingerprint
    }

    /**
     * Generates a new fingerprint string: `UUID|mac|hostname`.
     *
     * Exposed `internal` so tests can call it directly to verify format.
     */
    internal fun generate(): String {
        val uuid     = UUID.randomUUID().toString()
        val mac      = firstMac() ?: "00:00:00:00:00:00"
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("localhost")
        return "$uuid|$mac|$hostname"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPaths(dataDir: String?): List<String> =
        if (dataDir != null)
            listOf(File(dataDir, ".agent_fingerprint").absolutePath) + defaultPaths
        else defaultPaths

    private fun tryWrite(path: String, content: String): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    }.getOrDefault(false)

    private fun firstMac(): String? =
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { !it.isLoopback && it.hardwareAddress != null }
            ?.map { it.hardwareAddress.joinToString(":") { b -> "%02x".format(b) } }
            ?.firstOrNull()
}
