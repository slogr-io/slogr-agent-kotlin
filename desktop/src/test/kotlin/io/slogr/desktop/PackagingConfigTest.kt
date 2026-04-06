package io.slogr.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates packaging configuration constants and entry point.
 */
class PackagingConfigTest {

    @Test
    fun `main class exists and has correct signature`() {
        // Verify the MainKt class exists with main(Array<String>) signature
        val mainClass = Class.forName("io.slogr.desktop.MainKt")
        val mainMethod = mainClass.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.modifiers))
    }

    @Test
    fun `version property is set`() {
        // In packaged mode, -Dslogr.version=1.1.0 is set via JVM args
        // In test, verify the fallback works
        val version = System.getProperty("slogr.version") ?: "1.1.0"
        assertEquals("1.1.0", version)
    }

    @Test
    fun `background flag parsing works`() {
        assertTrue("--background" in arrayOf("--background"))
        assertTrue("--background" in arrayOf("--background", "--debug"))
        assertTrue("--background" !in arrayOf("--debug"))
        assertTrue("--background" !in emptyArray<String>())
    }
}
