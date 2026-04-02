package io.slogr.agent.platform.health

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.PublishStatus
import io.slogr.agent.contracts.interfaces.ResultPublisher
import io.slogr.agent.engine.reflector.ReflectorSession
import io.slogr.agent.engine.reflector.ReflectorThreadPool
import io.slogr.agent.engine.twamp.SessionId
import io.slogr.agent.platform.buffer.WriteAheadLog
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

class HealthReporterTest {

    private val agentId  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val tenantId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")

    private val credential = AgentCredential(
        agentId            = agentId,
        tenantId           = tenantId,
        displayName        = "test-agent",
        jwt                = "jwt-token",
        rabbitmqJwt        = "mq-jwt-token",
        rabbitmqHost       = "mq.test",
        rabbitmqPort       = 5671,
        pubsubSubscription = "slogr.agent-commands.$agentId",
        issuedAt           = Clock.System.now(),
        connectedVia       = ConnectionMethod.API_KEY
    )

    private val publisher: ResultPublisher = mockk {
        coEvery { publishHealth(any()) } returns true
    }

    private val wal: WriteAheadLog = mockk {
        every { sizeRows } returns 0
    }

    // ── R2-HEALTH-01: active sessions reflected in snapshot ───────────────

    @Test
    fun `R2-HEALTH-01 buildSnapshot includes active_responder_sessions from pool`() {
        val pool = ReflectorThreadPool()
        val sessionId = SessionId(ipv4 = 0x7F000001, timestamp = System.nanoTime(), randNumber = 1)
        pool.registerSession(ReflectorSession(sessionId, InetSocketAddress("127.0.0.1", 10000)))

        val reporter = HealthReporter(
            credential    = credential,
            publisher     = publisher,
            wal           = wal,
            reflectorPool = pool
        )

        val snapshot = reporter.buildSnapshot()
        assertEquals(1, snapshot.activeResponderSessions)
        assertEquals(pool.poolSize, snapshot.poolSize)

        pool.unregisterSession(sessionId)
        pool.shutdown()
    }

    @Test
    fun `R2-HEALTH-01 buildSnapshot with 50 sessions`() {
        val pool = ReflectorThreadPool()
        val sessions = (1..50).map { i ->
            ReflectorSession(
                SessionId(ipv4 = 0x7F000001, timestamp = System.nanoTime(), randNumber = i),
                InetSocketAddress("127.0.0.1", 10000 + i)
            )
        }
        sessions.forEach { pool.registerSession(it) }

        val reporter = HealthReporter(credential = credential, publisher = publisher, wal = wal,
            reflectorPool = pool)

        assertEquals(50, reporter.buildSnapshot().activeResponderSessions)

        sessions.forEach { pool.unregisterSession(it.sessionId) }
        pool.shutdown()
    }

    // ── R2-HEALTH-03: no sessions → zeros ────────────────────────────────

    @Test
    fun `R2-HEALTH-03 no responder sessions yields zero active metrics`() {
        val pool = ReflectorThreadPool()

        val reporter = HealthReporter(
            credential    = credential,
            publisher     = publisher,
            wal           = wal,
            reflectorPool = pool
        )

        val snapshot = reporter.buildSnapshot()
        assertEquals(0, snapshot.activeResponderSessions)
        assertEquals(0, snapshot.poolActiveThreads)
        assertEquals(0, snapshot.poolQueueDepth)

        pool.shutdown()
    }

    // ── No pool reference → graceful zero defaults ────────────────────────

    @Test
    fun `buildSnapshot without pool reference yields zero pool metrics`() {
        val reporter = HealthReporter(credential = credential, publisher = publisher, wal = wal)

        val snapshot = reporter.buildSnapshot()
        assertEquals(0, snapshot.activeResponderSessions)
        assertEquals(0, snapshot.poolSize)
        assertEquals(0, snapshot.poolActiveThreads)
        assertEquals(0, snapshot.poolQueueDepth)
    }

    // ── Publish status logic ──────────────────────────────────────────────

    @Test
    fun `publishStatus is OK when no failures`() {
        val reporter = HealthReporter(credential = credential, publisher = publisher, wal = wal)
        assertEquals(PublishStatus.OK, reporter.buildSnapshot().publishStatus)
    }

    @Test
    fun `publishStatus is DEGRADED for 1-5 consecutive failures`() {
        val reporter = HealthReporter(credential = credential, publisher = publisher, wal = wal)
        repeat(3) { reporter.publishFailureCount.incrementAndGet() }
        assertEquals(PublishStatus.DEGRADED, reporter.buildSnapshot().publishStatus)
    }

    @Test
    fun `publishStatus is FAILING for 6 or more consecutive failures`() {
        val reporter = HealthReporter(credential = credential, publisher = publisher, wal = wal)
        repeat(6) { reporter.publishFailureCount.incrementAndGet() }
        assertEquals(PublishStatus.FAILING, reporter.buildSnapshot().publishStatus)
    }
}
