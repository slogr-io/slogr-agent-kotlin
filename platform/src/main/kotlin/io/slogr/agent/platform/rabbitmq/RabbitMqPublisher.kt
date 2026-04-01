package io.slogr.agent.platform.rabbitmq

import com.rabbitmq.client.AMQP
import io.slogr.agent.contracts.AgentCredential
import io.slogr.agent.contracts.HealthSnapshot
import io.slogr.agent.contracts.MeasurementResult
import io.slogr.agent.contracts.TracerouteResult
import io.slogr.agent.contracts.interfaces.ResultPublisher
import io.slogr.agent.platform.buffer.WriteAheadLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Date

/**
 * Publishes measurement results to RabbitMQ.
 *
 * Write-before-publish: entries are written to the WAL before being sent.
 * On broker ACK the WAL entry is acknowledged. On NACK or timeout the entry
 * remains pending for [WalReplayWorker].
 *
 * Exchange: `slogr.measurements` (topic, durable — must exist on the broker).
 * Routing keys: `agent.{agentId}.twamp | traceroute | health`
 *
 * Deduplication: identical consecutive results for the same session are dropped.
 */
class RabbitMqPublisher(
    private val credential: AgentCredential,
    private val wal: WriteAheadLog,
    private val rabbitConn: RabbitMqConnection,
    private val dedup: DeduplicationCache = DeduplicationCache(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val confirmTimeoutMs: Long = 5_000
) : ResultPublisher {

    private val log      = LoggerFactory.getLogger(RabbitMqPublisher::class.java)
    private val exchange = "slogr.measurements"
    private val agentId  = credential.agentId

    override suspend fun publishMeasurement(result: MeasurementResult): Boolean {
        val payload = json.encodeToString(MeasurementResult.serializer(), result)
        if (dedup.isDuplicate(result.sessionId, payload)) {
            log.debug("Skipping duplicate measurement for session ${result.sessionId}")
            return true
        }
        val walId = wal.append("twamp", payload)
        return doPublish("agent.$agentId.twamp", payload, walId)
    }

    override suspend fun publishTraceroute(result: TracerouteResult): Boolean {
        val payload = json.encodeToString(TracerouteResult.serializer(), result)
        val walId   = wal.append("traceroute", payload)
        return doPublish("agent.$agentId.traceroute", payload, walId)
    }

    override suspend fun publishHealth(snapshot: HealthSnapshot): Boolean {
        val payload = json.encodeToString(HealthSnapshot.serializer(), snapshot)
        val walId   = wal.append("health", payload)
        return doPublish("agent.$agentId.health", payload, walId)
    }

    override suspend fun flush() {
        // WAL replay drains pending entries; flush is a no-op here (WAL is the buffer)
    }

    /**
     * Publish a raw JSON payload that was previously written to the WAL.
     * Used by [WalReplayWorker] to replay pending entries.
     */
    suspend fun publishRaw(type: String, dataJson: String): Boolean {
        val routingKey = "agent.$agentId.$type"
        return doPublishRaw(routingKey, dataJson, walId = null)
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private suspend fun doPublish(routingKey: String, payload: String, walId: String): Boolean =
        doPublishRaw(routingKey, payload, walId)

    private suspend fun doPublishRaw(routingKey: String, payload: String, walId: String?): Boolean =
        withContext(Dispatchers.IO) {
            val ch = rabbitConn.channel()
            if (ch == null) {
                log.debug("RabbitMQ not connected — entry will be replayed from WAL")
                return@withContext false
            }
            runCatching {
                val props = AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding("utf-8")
                    .deliveryMode(2)           // persistent
                    .timestamp(Date())
                    .headers(mapOf(
                        "schema_version" to 1,
                        "agent_id"       to agentId.toString(),
                        "tenant_id"      to credential.tenantId.toString()
                    ))
                    .build()
                ch.basicPublish(exchange, routingKey, props, payload.toByteArray(Charsets.UTF_8))
                val confirmed = ch.waitForConfirms(confirmTimeoutMs)
                if (confirmed && walId != null) {
                    wal.ack(walId)
                }
                confirmed
            }.getOrElse { e ->
                log.warn("RabbitMQ publish error: ${e.message}")
                false
            }
        }
}
