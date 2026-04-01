package io.slogr.agent.platform.buffer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WriteAheadLogTest {

    @TempDir
    lateinit var tmpDir: File

    private fun wal() = WriteAheadLog(tmpDir.absolutePath)

    // ── Append ────────────────────────────────────────────────────────────────

    @Test
    fun `append returns unique IDs`() {
        val w = wal()
        val id1 = w.append("twamp", "{}")
        val id2 = w.append("twamp", "{}")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `appended entry appears in unackedEntries`() {
        val w = wal()
        val id = w.append("twamp", """{"foo":"bar"}""")
        val entries = w.unackedEntries()
        assertEquals(1, entries.size)
        assertEquals(id, entries.first().id)
        assertEquals("twamp", entries.first().type)
    }

    // ── Ack ───────────────────────────────────────────────────────────────────

    @Test
    fun `acked entry is removed from unackedEntries`() {
        val w = wal()
        val id = w.append("health", "{}")
        w.ack(id)
        assertEquals(0, w.unackedEntries().size)
    }

    @Test
    fun `acking one entry does not remove others`() {
        val w = wal()
        val id1 = w.append("twamp", "{}")
        val id2 = w.append("traceroute", "{}")
        w.ack(id1)
        val remaining = w.unackedEntries()
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining.first().id)
    }

    // ── SizeRows ──────────────────────────────────────────────────────────────

    @Test
    fun `sizeRows matches unacked count`() {
        val w = wal()
        w.append("twamp", "{}")
        w.append("twamp", "{}")
        assertEquals(2, w.sizeRows)
    }

    // ── Compact ───────────────────────────────────────────────────────────────

    @Test
    fun `compact removes acked entries from WAL file`() {
        val w = wal()
        val id1 = w.append("twamp", "{}")
        val id2 = w.append("twamp", "{}")
        w.ack(id1)
        w.compact()
        val entries = w.unackedEntries()
        assertEquals(1, entries.size)
        assertEquals(id2, entries.first().id)
    }

    @Test
    fun `compact on empty WAL does not throw`() {
        assertDoesNotThrow { wal().compact() }
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    fun `unackedEntries preserves insertion order`() {
        val w = wal()
        val ids = (1..5).map { w.append("twamp", """{"seq":$it}""") }
        assertEquals(ids, w.unackedEntries().map { it.id })
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    fun `entries survive across WAL instances (restart simulation)`() {
        val w1 = WriteAheadLog(tmpDir.absolutePath)
        val id = w1.append("twamp", """{"x":1}""")

        val w2 = WriteAheadLog(tmpDir.absolutePath)
        val entries = w2.unackedEntries()
        assertEquals(1, entries.size)
        assertEquals(id, entries.first().id)
    }

    // ── Eviction ──────────────────────────────────────────────────────────────

    @Test
    fun `eviction keeps only most recent entries when maxRows exceeded`() {
        val w = WriteAheadLog(tmpDir.absolutePath, maxRows = 5)
        repeat(10) { w.append("twamp", """{"i":$it}""") }
        assertTrue(w.sizeRows <= 5)
    }
}
