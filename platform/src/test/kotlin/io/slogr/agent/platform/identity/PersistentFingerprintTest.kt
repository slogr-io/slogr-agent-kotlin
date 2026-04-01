package io.slogr.agent.platform.identity

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PersistentFingerprintTest {

    @TempDir
    lateinit var tempDir: File

    /** Restrict PersistentFingerprint to only the test's temp dir, avoiding
     *  production paths that may already exist on the build machine. */
    @BeforeEach
    fun isolatePaths() {
        PersistentFingerprint.testPaths = listOf(File(tempDir, ".agent_fingerprint").absolutePath)
    }

    @AfterEach
    fun restorePaths() {
        PersistentFingerprint.testPaths = null
    }

    // ── R2-FP-01: First boot — generate + persist ──────────────────────────────

    @Test
    fun `R2-FP-01 first boot generates fingerprint and persists it`() {
        val fp = PersistentFingerprint.get()

        assertTrue(fp.isNotEmpty())
        val file = File(tempDir, ".agent_fingerprint")
        assertTrue(file.exists(), "Fingerprint file must be written on first call")
        assertEquals(fp, file.readText().trim())
    }

    // ── R2-FP-02: Second boot — reads same fingerprint from file ───────────────

    @Test
    fun `R2-FP-02 second call returns same fingerprint`() {
        val first  = PersistentFingerprint.get()
        val second = PersistentFingerprint.get()
        assertEquals(first, second)
    }

    // ── R2-FP-03: Cloned VMs — UUID component ensures divergence ──────────────

    @Test
    fun `R2-FP-03 two generated fingerprints have different UUID components`() {
        val fp1 = PersistentFingerprint.generate()
        val fp2 = PersistentFingerprint.generate()
        val uuid1 = fp1.substringBefore("|")
        val uuid2 = fp2.substringBefore("|")
        assertNotEquals(uuid1, uuid2, "UUID component must differ between generations")
    }

    // ── R2-FP-04: Container restart — pre-existing file is used ───────────────

    @Test
    fun `R2-FP-04 pre-existing fingerprint file is returned without regeneration`() {
        val preWritten = "pre-existing-fingerprint-value"
        File(tempDir, ".agent_fingerprint").writeText(preWritten)

        val result = PersistentFingerprint.get()
        assertEquals(preWritten, result)
    }

    // ── Format check ───────────────────────────────────────────────────────────

    @Test
    fun `generated fingerprint has three pipe-separated parts with valid UUID first`() {
        val fp = PersistentFingerprint.generate()
        val parts = fp.split("|")
        assertEquals(3, parts.size, "Expected format: UUID|mac|hostname")
        assertDoesNotThrow({ java.util.UUID.fromString(parts[0]) },
            "First part must be a valid UUID")
    }
}
