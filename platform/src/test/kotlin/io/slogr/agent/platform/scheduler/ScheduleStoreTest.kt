package io.slogr.agent.platform.scheduler

import io.slogr.agent.contracts.*
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.util.UUID

class ScheduleStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val store by lazy { ScheduleStore(tempDir.absolutePath) }

    // ── Missing file → null ───────────────────────────────────────────────────

    @Test
    fun `load returns null when file does not exist`() {
        assertNull(store.load())
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `save and load round-trip preserves schedule`() {
        val schedule = makeSchedule()
        store.save(schedule)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.sessions.size)
        assertEquals("voip", loaded.sessions[0].profile.name)
        assertEquals(300, loaded.sessions[0].intervalSeconds)
    }

    // ── Corrupt file → null ───────────────────────────────────────────────────

    @Test
    fun `corrupt file returns null without throwing`() {
        File(tempDir, "schedule.json").writeText("this is not valid json {{{{")
        assertNull(store.load())
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    fun `clear removes schedule file`() {
        store.save(makeSchedule())
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeSchedule() = Schedule(
        sessions = listOf(SessionConfig(
            pathId = UUID.randomUUID(),
            targetIp = InetAddress.getLoopbackAddress(),
            profile = SlaProfile(
                name = "voip", nPackets = 100, intervalMs = 20L, waitTimeMs = 500L,
                dscp = 46, packetSize = 64, timingMode = TimingMode.FIXED,
                rttGreenMs = 30f, rttRedMs = 80f, jitterGreenMs = 5f, jitterRedMs = 15f,
                lossGreenPct = 0.5f, lossRedPct = 2f
            )
        )),
        receivedAt = Clock.System.now()
    )
}
