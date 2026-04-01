package io.slogr.agent.platform.rabbitmq

import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.ConnectionMethod
import io.slogr.agent.contracts.Direction
import io.slogr.agent.contracts.HealthSnapshot
import io.slogr.agent.contracts.MeasurementBundle
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.PublishStatus
import io.slogr.agent.contracts.SlaGrade
import io.slogr.agent.contracts.SlaProfile
import io.slogr.agent.contracts.TimingMode
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.platform.buffer.WriteAheadLog
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * Integration tests for [RabbitMqPublisher].
 *
 * These tests require a running RabbitMQ instance.
 * Run via: `./gradlew :platform:integrationTest`
 *
 * TODO: wire Testcontainers RabbitMQ when Docker is available in CI.
 */
@Disabled("Requires Docker/Testcontainers — enable in CI")
class RabbitMqPublishTest {

    @TempDir
    lateinit var tmpDir: File

    @Test
    fun `publish measurement result succeeds and WAL entry is acked`() {
        // TODO: start Testcontainers RabbitMQ
        // val rabbit = RabbitMQContainer("rabbitmq:3.13-management").apply { start() }
        // val cred = fakeCredential(rabbit.host, rabbit.amqpPort)
        // val wal  = WriteAheadLog(tmpDir.absolutePath)
        // val conn = RabbitMqConnection(cred)
        // val pub  = RabbitMqPublisher(cred, wal, conn)
        // val result = fakeMeasurement()
        // assertTrue(runBlocking { pub.publishMeasurement(result) })
        // assertEquals(0, wal.sizeRows)
    }

    @Test
    fun `WAL entries are replayed after reconnect`() {
        // TODO: wire replay test
    }

    @Test
    fun `duplicate results are not published twice`() {
        // TODO: wire dedup test
    }
}
