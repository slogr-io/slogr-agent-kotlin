package io.slogr.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class VersionTest {

    @Test
    fun `version command prints version string containing 1_0_0`() {
        val out = captureStdout { run(arrayOf("version")) }
        assertTrue(out.contains("1.0.8"), "Expected '1.0.8' in output but got: $out")
    }

    @Test
    fun `version command prints slogr-agent prefix`() {
        val out = captureStdout { run(arrayOf("version")) }
        assertTrue(out.startsWith("slogr-agent "), "Expected output to start with 'slogr-agent ' but got: $out")
    }

    @Test
    fun `unknown command returns exit code 2`() {
        val code = run(arrayOf("nonexistent-command"))
        assertEquals(2, code, "Expected exit code 2 for unknown command")
    }

    @Test
    fun `version command returns exit code 0`() {
        val code = captureStdoutReturning { run(arrayOf("version")) }
        assertEquals(0, code)
    }

    @Test
    fun `help flag prints formatted help containing subcommand names`() {
        val out = captureStdout { run(arrayOf("--help")) }
        assertTrue(out.contains("check"), "Help output should list 'check' subcommand but got: $out")
        assertTrue(out.contains("daemon"), "Help output should list 'daemon' subcommand but got: $out")
        assertTrue(out.contains("version"), "Help output should list 'version' subcommand but got: $out")
    }

    @Test
    fun `help flag returns exit code 0`() {
        val code = captureStdoutReturning { run(arrayOf("--help")) }
        assertEquals(0, code)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun captureStdout(block: () -> Unit): String {
        val old = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString().trim()
    }

    private fun <T> captureStdoutReturning(block: () -> T): T {
        val old = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf))
        return try {
            block()
        } finally {
            System.setOut(old)
        }
    }
}
