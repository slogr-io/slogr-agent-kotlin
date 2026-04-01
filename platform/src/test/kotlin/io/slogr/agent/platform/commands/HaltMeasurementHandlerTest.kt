package io.slogr.agent.platform.commands

import io.mockk.mockk
import io.slogr.agent.platform.scheduler.ScheduleStore
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class HaltMeasurementHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private val agentId   = UUID.randomUUID()
    private val tenantId  = UUID.randomUUID()
    private val commandId = UUID.randomUUID()

    private val scheduler by lazy {
        TestScheduler(engine = mockk(relaxed = true), onResult = { _, _ -> })
    }

    private val store by lazy { ScheduleStore(tempDir.absolutePath) }

    private fun handler(nowMs: () -> Long = { System.currentTimeMillis() }) =
        HaltMeasurementHandler(
            agentId       = agentId,
            tenantId      = tenantId,
            scheduler     = scheduler,
            scheduleStore = store,
            nowMs         = nowMs
        )

    // ── R2-HALT-01: halt_measurement → acked, schedule cleared ────────────────

    @Test
    fun `R2-HALT-01 halt returns acked and clears persisted schedule`() = runBlocking {
        val response = handler().handle(commandId, Json.parseToJsonElement("{}"))

        assertEquals("acked", response.status)
        assertNotNull(response.commandId)

        val scheduleFile = File(tempDir, "schedule.json")
        assertFalse(scheduleFile.exists(), "schedule.json must be removed after halt")
    }

    // ── R2-HALT-02: response contains correct IDs ─────────────────────────────

    @Test
    fun `R2-HALT-02 response carries correct agent and tenant IDs`() = runBlocking {
        val response = handler().handle(commandId, Json.parseToJsonElement("{}"))
        assertEquals(agentId,   response.agentId)
        assertEquals(tenantId,  response.tenantId)
        assertEquals(commandId, response.commandId)
    }

    // ── R2-HALT-03: timeout exceeded → timed_out status ───────────────────────

    @Test
    fun `R2-HALT-03 when elapsed exceeds timeout_seconds response is timed_out`() = runBlocking {
        // Inject a clock that jumps 31 s between t0 and the end measurement,
        // making elapsed (31_000 ms) > timeoutMs (30_000 ms).
        var tick = 0L
        val fakeClock: () -> Long = { tick.also { tick += 31_000L } }

        val response = handler(nowMs = fakeClock)
            .handle(commandId, Json.parseToJsonElement("{}"))

        assertEquals("timed_out", response.status)
    }

    // ── R2-HALT-04: custom timeout_seconds payload is respected ───────────────

    @Test
    fun `R2-HALT-04 custom timeout_seconds in payload is honoured`() = runBlocking {
        // timeout_seconds: 5 → timeoutMs = 5 000; injected clock gives elapsed = 6 000
        var tick = 0L
        val fakeClock: () -> Long = { tick.also { tick += 6_000L } }

        val response = handler(nowMs = fakeClock)
            .handle(commandId, Json.parseToJsonElement("""{"timeout_seconds":5}"""))

        assertEquals("timed_out", response.status)
    }
}
