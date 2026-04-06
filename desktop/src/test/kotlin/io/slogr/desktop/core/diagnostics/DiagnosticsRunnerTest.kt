package io.slogr.desktop.core.diagnostics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsRunnerTest {

    @Test
    fun `runAll returns three diagnostic results`() = runBlocking {
        val results = DiagnosticsRunner.runAll()
        assertEquals(3, results.size)
    }

    @Test
    fun `each result has a name and detail`() = runBlocking {
        val results = DiagnosticsRunner.runAll()
        results.forEach { result ->
            assertTrue(result.name.isNotBlank(), "Name should not be blank")
            assertTrue(result.detail.isNotBlank(), "Detail should not be blank")
        }
    }

    @Test
    fun `diagnostic names are DNS, HTTPS, and TWAMP`() = runBlocking {
        val results = DiagnosticsRunner.runAll()
        val names = results.map { it.name }
        assertTrue(names.any { it.contains("DNS") })
        assertTrue(names.any { it.contains("HTTPS") })
        assertTrue(names.any { it.contains("TWAMP") })
    }
}
