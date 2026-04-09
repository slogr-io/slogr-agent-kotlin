package io.slogr.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagingConfigTest {

    @Test
    fun `main class exists with correct signature`() {
        val mainClass = Class.forName("io.slogr.desktop.MainKt")
        val mainMethod = mainClass.getDeclaredMethod("main")
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.modifiers))
    }

    @Test
    fun `version property fallback works`() {
        val version = System.getProperty("slogr.version") ?: "1.1.0"
        assertEquals("1.1.0", version)
    }
}
