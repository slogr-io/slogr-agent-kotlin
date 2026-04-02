package io.slogr.agent.platform.commands

import io.mockk.mockk
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.platform.scheduler.ScheduleStore
import io.slogr.agent.platform.scheduler.TestScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class SetScheduleHandlerTest {

    @TempDir
    lateinit var tempDir: File

    private val agentId   = UUID.randomUUID()
    private val tenantId  = UUID.randomUUID()
    private val commandId = UUID.randomUUID()
    private val pathId    = UUID.randomUUID()

    private val testProfile = SlaProfile(
        name = "internet", nPackets = 10, intervalMs = 20L, waitTimeMs = 500L,
        dscp = 0, packetSize = 64, timingMode = TimingMode.FIXED,
        rttGreenMs = 100f, rttRedMs = 300f,
        jitterGreenMs = 20f, jitterRedMs = 60f,
        lossGreenPct = 1f, lossRedPct = 5f
    )

    private val store by lazy { ScheduleStore(tempDir.absolutePath) }

    private val scheduler by lazy {
        TestScheduler(engine = mockk(relaxed = true), onResult = { _, _ -> })
    }

    private fun handler() = SetScheduleHandler(
        agentId         = agentId,
        tenantId        = tenantId,
        scheduler       = scheduler,
        scheduleStore   = store,
        profileRegistry = { if (it == "internet") testProfile else null }
    )

    // ── R2-MPORT-03: tcp_probe_ports omitted → default [443] ──────────────────

    @Test
    fun `R2-MPORT-03 tcp_probe_ports omitted defaults to 443`() = runBlocking {
        val payload = """
            {
              "targets": [{"target_ip":"127.0.0.1","target_port":862,"path_id":"$pathId"}],
              "profile": "internet"
            }
        """.trimIndent()
        handler().handle(commandId, Json.parseToJsonElement(payload))

        val saved = store.load()!!
        assertEquals(1, saved.sessions.size)
        assertEquals(listOf(443), saved.sessions[0].tcpProbePorts)
    }

    // ── R2-MPORT-04: 6 ports supplied → first 5, warning logged ───────────────

    @Test
    fun `R2-MPORT-04 six ports capped to first five`() = runBlocking {
        val payload = """
            {
              "targets": [{
                "target_ip":"127.0.0.1","target_port":862,"path_id":"$pathId",
                "tcp_probe_ports":[443,80,1433,6379,5432,27017]
              }],
              "profile": "internet"
            }
        """.trimIndent()
        handler().handle(commandId, Json.parseToJsonElement(payload))

        val saved = store.load()!!
        assertEquals(listOf(443, 80, 1433, 6379, 5432), saved.sessions[0].tcpProbePorts)
    }

    // ── Explicit tcp_probe_ports respected exactly ──────────────────────────────

    @Test
    fun `explicit tcp_probe_ports list is preserved`() = runBlocking {
        val payload = """
            {
              "targets": [{
                "target_ip":"127.0.0.1","target_port":862,"path_id":"$pathId",
                "tcp_probe_ports":[443,1433,6379]
              }],
              "profile": "internet"
            }
        """.trimIndent()
        handler().handle(commandId, Json.parseToJsonElement(payload))

        val saved = store.load()!!
        assertEquals(listOf(443, 1433, 6379), saved.sessions[0].tcpProbePorts)
    }

    // ── Exactly 5 ports: no capping ────────────────────────────────────────────

    @Test
    fun `exactly five ports accepted without capping`() = runBlocking {
        val payload = """
            {
              "targets": [{
                "target_ip":"127.0.0.1","target_port":862,"path_id":"$pathId",
                "tcp_probe_ports":[443,80,1433,6379,5432]
              }],
              "profile": "internet"
            }
        """.trimIndent()
        handler().handle(commandId, Json.parseToJsonElement(payload))

        val saved = store.load()!!
        assertEquals(listOf(443, 80, 1433, 6379, 5432), saved.sessions[0].tcpProbePorts)
    }

    // ── Missing targets → failed response ─────────────────────────────────────

    @Test
    fun `missing targets returns failed response`() = runBlocking {
        val payload = """{"profile":"internet"}"""
        val response = handler().handle(commandId, Json.parseToJsonElement(payload))
        assertEquals("failed", response.status)
        assertTrue(response.error?.contains("missing targets") == true)
    }

    // ── Unknown profile → failed response ─────────────────────────────────────

    @Test
    fun `unknown profile returns failed response`() = runBlocking {
        val payload = """
            {
              "targets":[{"target_ip":"127.0.0.1","path_id":"$pathId"}],
              "profile":"nonexistent"
            }
        """.trimIndent()
        val response = handler().handle(commandId, Json.parseToJsonElement(payload))
        assertEquals("failed", response.status)
        assertTrue(response.error?.contains("unknown profile") == true)
    }
}
